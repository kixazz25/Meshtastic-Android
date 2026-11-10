package com.grouptrack.shared.text

import kotlin.math.abs
import kotlin.math.truncate
import platform.posix.fma

/**
 * GTMATH2B-2026-11-10: ANDROID's own pow, in Kotlin, for the iPad (GtMath.pow's iOS actual). On Android GtMath.pow IS java.lang.Math.pow
 * -- unchanged.
 * WHY: GtRead's haversine (2.7e) squares with x.pow(2) = Math.pow(x, 2.0). MATHPOW 11-10 on Droid 1 (1,500,000 values: 1,200,000 real
 * sin(dLat/2), sin(dLon/2) + seeded random): Android's pow(x, 2.0) is NOT x * x on 1,266 of them (1,009 real) -- it is not correctly
 * rounded (up to 0.5056 ulp; x * x is exact-rounded), so the first plan (x * x on the iPad) would have been wrong.
 * WHAT ANDROID RUNS: Arm's optimized-routines pow (bionic's arm64 libm; the same code glibc uses on 64-bit Arm). Proven 11-10:
 * glibc 2.35's pow on aarch64 = the Droid on 1,500,000 / 1,500,000. This is that algorithm (math/pow.c, Copyright (c) 2018-2026 Arm
 * Limited, SPDX-License-Identifier: MIT OR Apache-2.0 WITH LLVM-exception) as compiled for arm64: HAVE_FAST_FMA = 1 (the two fma()
 * calls in log_inline and the one in pow -- a hardware fused multiply-add, single rounding, IEEE-exact on every platform),
 * TOINT_INTRINSICS = 1 (round half away from zero), N = 128 tables (pow_log_data.c, exp_data.c; raw bits below, extracted as text),
 * AND clang's default contraction (-ffp-contract=on): every a*b + c INSIDE ONE C expression is one fused multiply-add (written here
 * as fma, the C expression beside it). Measured 11-10 on the 1,500,000 Droid values: as written here 0 differ; no contraction
 * 1,052 differ; contraction across statements too (gcc -ffp-contract=fast) 3 differ. (bionic's msun cos/sin are NOT contracted --
 * MATHPROBE 11-10 -- so GtMathPort's cos/sin use none: each routine is ported as Android compiles it.)
 * BRANCHES GroupTrack never reaches (other y, negative x, subnormal x, results near overflow / underflow): checked 11-10 against
 * glibc's pow on 64-bit Arm (the same routine; = Android on the proven path) on 215,833 seeded pairs -- 0 differ, with the underflow
 * case's scale + scale*tmp NOT fused (fused there, 7 of them differed by 1 bit). Not proven on the Droid itself.
 * PROVEN for the inputs GroupTrack gives it: y = 2.0 (GtRead, the only use) -- MATHPOWPORT on the iPad vs the Droid's 1,500,000.
 * The other branches (negative x with odd y, subnormal x, overflow / underflow, |y| tiny or huge) are ported as written, not proven.
 */
internal object GtMathPow {
    private fun f(u: ULong): Double = Double.fromBits(u.toLong())
    private const val N = 128
    private const val OFF = 0x3fe6955500000000L
    private const val SIGN_BIAS = 0x800L shl 7
    private val INF_BITS = Double.POSITIVE_INFINITY.toRawBits()
    private val ONE_BITS = 1.0.toRawBits()
    /** 2^e exactly for a normal exponent (C's 0x1pE literals; Kotlin has no hex floats). */
    private fun p2(e: Int): Double = Double.fromBits((e + 1023).toLong() shl 52)
    private val P2_769 = p2(769); private val P2_M767 = p2(-767); private val P2_1009 = p2(1009); private val P2_M1022 = p2(-1022)
    private val P2_M54 = p2(-54); private val P2_52 = p2(52)

    // ---- ARM optimized-routines pow tables (math/pow_log_data.c + math/exp_data.c, N = 128): raw bits, extracted 11-10 ----
    private val PL_LN2HI = f(0x3fe62e42fefa3800UL); private val PL_LN2LO = f(0x3d2ef35793c76730UL)
    private val PL_A = doubleArrayOf(f(0xbfe0000000000000UL), f(0xbfe5555555555560UL), f(0x3fe0000000000006UL), f(0x3fe999999959554eUL), f(0xbfe555555529a47aUL), f(0xbff2495b9b4845e9UL), f(0x3ff0002b8b263fc3UL))
    private val PL_INVC = longArrayOf(
        0x3ff6a00000000000UL.toLong(), 0x3ff6800000000000UL.toLong(), 0x3ff6600000000000UL.toLong(), 0x3ff6400000000000UL.toLong(),
        0x3ff6200000000000UL.toLong(), 0x3ff6000000000000UL.toLong(), 0x3ff5e00000000000UL.toLong(), 0x3ff5c00000000000UL.toLong(),
        0x3ff5a00000000000UL.toLong(), 0x3ff5800000000000UL.toLong(), 0x3ff5600000000000UL.toLong(), 0x3ff5600000000000UL.toLong(),
        0x3ff5400000000000UL.toLong(), 0x3ff5200000000000UL.toLong(), 0x3ff5000000000000UL.toLong(), 0x3ff4e00000000000UL.toLong(),
        0x3ff4c00000000000UL.toLong(), 0x3ff4a00000000000UL.toLong(), 0x3ff4a00000000000UL.toLong(), 0x3ff4800000000000UL.toLong(),
        0x3ff4600000000000UL.toLong(), 0x3ff4400000000000UL.toLong(), 0x3ff4200000000000UL.toLong(), 0x3ff4000000000000UL.toLong(),
        0x3ff4000000000000UL.toLong(), 0x3ff3e00000000000UL.toLong(), 0x3ff3c00000000000UL.toLong(), 0x3ff3a00000000000UL.toLong(),
        0x3ff3a00000000000UL.toLong(), 0x3ff3800000000000UL.toLong(), 0x3ff3600000000000UL.toLong(), 0x3ff3400000000000UL.toLong(),
        0x3ff3400000000000UL.toLong(), 0x3ff3200000000000UL.toLong(), 0x3ff3000000000000UL.toLong(), 0x3ff3000000000000UL.toLong(),
        0x3ff2e00000000000UL.toLong(), 0x3ff2c00000000000UL.toLong(), 0x3ff2c00000000000UL.toLong(), 0x3ff2a00000000000UL.toLong(),
        0x3ff2800000000000UL.toLong(), 0x3ff2600000000000UL.toLong(), 0x3ff2600000000000UL.toLong(), 0x3ff2400000000000UL.toLong(),
        0x3ff2400000000000UL.toLong(), 0x3ff2200000000000UL.toLong(), 0x3ff2000000000000UL.toLong(), 0x3ff2000000000000UL.toLong(),
        0x3ff1e00000000000UL.toLong(), 0x3ff1c00000000000UL.toLong(), 0x3ff1c00000000000UL.toLong(), 0x3ff1a00000000000UL.toLong(),
        0x3ff1a00000000000UL.toLong(), 0x3ff1800000000000UL.toLong(), 0x3ff1600000000000UL.toLong(), 0x3ff1600000000000UL.toLong(),
        0x3ff1400000000000UL.toLong(), 0x3ff1400000000000UL.toLong(), 0x3ff1200000000000UL.toLong(), 0x3ff1000000000000UL.toLong(),
        0x3ff1000000000000UL.toLong(), 0x3ff0e00000000000UL.toLong(), 0x3ff0e00000000000UL.toLong(), 0x3ff0c00000000000UL.toLong(),
        0x3ff0c00000000000UL.toLong(), 0x3ff0a00000000000UL.toLong(), 0x3ff0a00000000000UL.toLong(), 0x3ff0800000000000UL.toLong(),
        0x3ff0800000000000UL.toLong(), 0x3ff0600000000000UL.toLong(), 0x3ff0400000000000UL.toLong(), 0x3ff0400000000000UL.toLong(),
        0x3ff0200000000000UL.toLong(), 0x3ff0200000000000UL.toLong(), 0x3ff0000000000000UL.toLong(), 0x3ff0000000000000UL.toLong(),
        0x3fefc00000000000UL.toLong(), 0x3fef800000000000UL.toLong(), 0x3fef400000000000UL.toLong(), 0x3fef000000000000UL.toLong(),
        0x3feec00000000000UL.toLong(), 0x3fee800000000000UL.toLong(), 0x3fee400000000000UL.toLong(), 0x3fee200000000000UL.toLong(),
        0x3fede00000000000UL.toLong(), 0x3feda00000000000UL.toLong(), 0x3fed600000000000UL.toLong(), 0x3fed400000000000UL.toLong(),
        0x3fed000000000000UL.toLong(), 0x3fecc00000000000UL.toLong(), 0x3feca00000000000UL.toLong(), 0x3fec600000000000UL.toLong(),
        0x3fec400000000000UL.toLong(), 0x3fec000000000000UL.toLong(), 0x3febe00000000000UL.toLong(), 0x3feba00000000000UL.toLong(),
        0x3feb800000000000UL.toLong(), 0x3feb400000000000UL.toLong(), 0x3feb200000000000UL.toLong(), 0x3feae00000000000UL.toLong(),
        0x3feac00000000000UL.toLong(), 0x3feaa00000000000UL.toLong(), 0x3fea600000000000UL.toLong(), 0x3fea400000000000UL.toLong(),
        0x3fea000000000000UL.toLong(), 0x3fe9e00000000000UL.toLong(), 0x3fe9c00000000000UL.toLong(), 0x3fe9a00000000000UL.toLong(),
        0x3fe9600000000000UL.toLong(), 0x3fe9400000000000UL.toLong(), 0x3fe9200000000000UL.toLong(), 0x3fe9000000000000UL.toLong(),
        0x3fe8c00000000000UL.toLong(), 0x3fe8a00000000000UL.toLong(), 0x3fe8800000000000UL.toLong(), 0x3fe8600000000000UL.toLong(),
        0x3fe8400000000000UL.toLong(), 0x3fe8200000000000UL.toLong(), 0x3fe7e00000000000UL.toLong(), 0x3fe7c00000000000UL.toLong(),
        0x3fe7a00000000000UL.toLong(), 0x3fe7800000000000UL.toLong(), 0x3fe7600000000000UL.toLong(), 0x3fe7400000000000UL.toLong(),
        0x3fe7200000000000UL.toLong(), 0x3fe7000000000000UL.toLong(), 0x3fe6e00000000000UL.toLong(), 0x3fe6c00000000000UL.toLong(),
    )
    private val PL_LOGC = longArrayOf(
        0xbfd62c82f2b9c800UL.toLong(), 0xbfd5d1bdbf580800UL.toLong(), 0xbfd5767717455800UL.toLong(), 0xbfd51aad872df800UL.toLong(),
        0xbfd4be5f95777800UL.toLong(), 0xbfd4618bc21c6000UL.toLong(), 0xbfd404308686a800UL.toLong(), 0xbfd3a64c55694800UL.toLong(),
        0xbfd347dd9a988000UL.toLong(), 0xbfd2e8e2bae12000UL.toLong(), 0xbfd2895a13de8800UL.toLong(), 0xbfd2895a13de8800UL.toLong(),
        0xbfd22941fbcf7800UL.toLong(), 0xbfd1c898c1699800UL.toLong(), 0xbfd1675cababa800UL.toLong(), 0xbfd1058bf9ae4800UL.toLong(),
        0xbfd0a324e2739000UL.toLong(), 0xbfd0402594b4d000UL.toLong(), 0xbfd0402594b4d000UL.toLong(), 0xbfcfb9186d5e4000UL.toLong(),
        0xbfcef0adcbdc6000UL.toLong(), 0xbfce27076e2af000UL.toLong(), 0xbfcd5c216b4fc000UL.toLong(), 0xbfcc8ff7c79aa000UL.toLong(),
        0xbfcc8ff7c79aa000UL.toLong(), 0xbfcbc286742d9000UL.toLong(), 0xbfcaf3c94e80c000UL.toLong(), 0xbfca23bc1fe2b000UL.toLong(),
        0xbfca23bc1fe2b000UL.toLong(), 0xbfc9525a9cf45000UL.toLong(), 0xbfc87fa06520d000UL.toLong(), 0xbfc7ab890210e000UL.toLong(),
        0xbfc7ab890210e000UL.toLong(), 0xbfc6d60fe719d000UL.toLong(), 0xbfc5ff3070a79000UL.toLong(), 0xbfc5ff3070a79000UL.toLong(),
        0xbfc526e5e3a1b000UL.toLong(), 0xbfc44d2b6ccb8000UL.toLong(), 0xbfc44d2b6ccb8000UL.toLong(), 0xbfc371fc201e9000UL.toLong(),
        0xbfc29552f81ff000UL.toLong(), 0xbfc1b72ad52f6000UL.toLong(), 0xbfc1b72ad52f6000UL.toLong(), 0xbfc0d77e7cd09000UL.toLong(),
        0xbfc0d77e7cd09000UL.toLong(), 0xbfbfec9131dbe000UL.toLong(), 0xbfbe27076e2b0000UL.toLong(), 0xbfbe27076e2b0000UL.toLong(),
        0xbfbc5e548f5bc000UL.toLong(), 0xbfba926d3a4ae000UL.toLong(), 0xbfba926d3a4ae000UL.toLong(), 0xbfb8c345d631a000UL.toLong(),
        0xbfb8c345d631a000UL.toLong(), 0xbfb6f0d28ae56000UL.toLong(), 0xbfb51b073f062000UL.toLong(), 0xbfb51b073f062000UL.toLong(),
        0xbfb341d7961be000UL.toLong(), 0xbfb341d7961be000UL.toLong(), 0xbfb16536eea38000UL.toLong(), 0xbfaf0a30c0118000UL.toLong(),
        0xbfaf0a30c0118000UL.toLong(), 0xbfab42dd71198000UL.toLong(), 0xbfab42dd71198000UL.toLong(), 0xbfa77458f632c000UL.toLong(),
        0xbfa77458f632c000UL.toLong(), 0xbfa39e87b9fec000UL.toLong(), 0xbfa39e87b9fec000UL.toLong(), 0xbf9f829b0e780000UL.toLong(),
        0xbf9f829b0e780000UL.toLong(), 0xbf97b91b07d58000UL.toLong(), 0xbf8fc0a8b0fc0000UL.toLong(), 0xbf8fc0a8b0fc0000UL.toLong(),
        0xbf7fe02a6b100000UL.toLong(), 0xbf7fe02a6b100000UL.toLong(), 0x0000000000000000UL.toLong(), 0x0000000000000000UL.toLong(),
        0x3f80101575890000UL.toLong(), 0x3f90205658938000UL.toLong(), 0x3f98492528c90000UL.toLong(), 0x3fa0415d89e74000UL.toLong(),
        0x3fa466aed42e0000UL.toLong(), 0x3fa894aa149fc000UL.toLong(), 0x3faccb73cdddc000UL.toLong(), 0x3faeea31c006c000UL.toLong(),
        0x3fb1973bd1466000UL.toLong(), 0x3fb3bdf5a7d1e000UL.toLong(), 0x3fb5e95a4d97a000UL.toLong(), 0x3fb700d30aeac000UL.toLong(),
        0x3fb9335e5d594000UL.toLong(), 0x3fbb6ac88dad6000UL.toLong(), 0x3fbc885801bc4000UL.toLong(), 0x3fbec739830a2000UL.toLong(),
        0x3fbfe89139dbe000UL.toLong(), 0x3fc1178e8227e000UL.toLong(), 0x3fc1aa2b7e23f000UL.toLong(), 0x3fc2d1610c868000UL.toLong(),
        0x3fc365fcb0159000UL.toLong(), 0x3fc4913d8333b000UL.toLong(), 0x3fc527e5e4a1b000UL.toLong(), 0x3fc6574ebe8c1000UL.toLong(),
        0x3fc6f0128b757000UL.toLong(), 0x3fc7898d85445000UL.toLong(), 0x3fc8beafeb390000UL.toLong(), 0x3fc95a5adcf70000UL.toLong(),
        0x3fca93ed3c8ae000UL.toLong(), 0x3fcb31d8575bd000UL.toLong(), 0x3fcbd087383be000UL.toLong(), 0x3fcc6ffbc6f01000UL.toLong(),
        0x3fcdb13db0d49000UL.toLong(), 0x3fce530effe71000UL.toLong(), 0x3fcef5ade4dd0000UL.toLong(), 0x3fcf991c6cb3b000UL.toLong(),
        0x3fd07138604d5800UL.toLong(), 0x3fd0c42d67616000UL.toLong(), 0x3fd1178e8227e800UL.toLong(), 0x3fd16b5ccbacf800UL.toLong(),
        0x3fd1bf99635a6800UL.toLong(), 0x3fd214456d0eb800UL.toLong(), 0x3fd2bef07cdc9000UL.toLong(), 0x3fd314f1e1d36000UL.toLong(),
        0x3fd36b6776be1000UL.toLong(), 0x3fd3c25277333000UL.toLong(), 0x3fd419b423d5e800UL.toLong(), 0x3fd4718dc271c800UL.toLong(),
        0x3fd4c9e09e173000UL.toLong(), 0x3fd522ae0738a000UL.toLong(), 0x3fd57bf753c8d000UL.toLong(), 0x3fd5d5bddf596000UL.toLong(),
    )
    private val PL_LOGCTAIL = longArrayOf(
        0x3cfab42428375680UL.toLong(), 0xbd1ca508d8e0f720UL.toLong(), 0xbd2362a4d5b6506dUL.toLong(), 0xbce684e49eb067d5UL.toLong(),
        0xbd041b6993293ee0UL.toLong(), 0x3d13d82f484c84ccUL.toLong(), 0x3cdc42f3ed820b3aUL.toLong(), 0x3d20b1c686519460UL.toLong(),
        0x3d25594dd4c58092UL.toLong(), 0x3d267b1e99b72bd8UL.toLong(), 0x3d15ca14b6cfb03fUL.toLong(), 0x3d15ca14b6cfb03fUL.toLong(),
        0xbd165a242853da76UL.toLong(), 0xbd1fafbc68e75404UL.toLong(), 0x3d1f1fc63382a8f0UL.toLong(), 0xbd26a8c4fd055a66UL.toLong(),
        0xbd0c6bee7ef4030eUL.toLong(), 0xbcf036b89ef42d7fUL.toLong(), 0xbcf036b89ef42d7fUL.toLong(), 0x3d0d572aab993c87UL.toLong(),
        0x3d2b26b79c86af24UL.toLong(), 0xbd172f4f543fff10UL.toLong(), 0x3d21ba91bbca681bUL.toLong(), 0x3d27794f689f8434UL.toLong(),
        0x3d27794f689f8434UL.toLong(), 0x3d194eb0318bb78fUL.toLong(), 0x3cba4e633fcd9066UL.toLong(), 0xbd258c64dc46c1eaUL.toLong(),
        0xbd258c64dc46c1eaUL.toLong(), 0xbd2ad1d904c1d4e3UL.toLong(), 0x3d2bbdbf7fdbfa09UL.toLong(), 0x3d2bdb9072534a58UL.toLong(),
        0x3d2bdb9072534a58UL.toLong(), 0xbd10e46aa3b2e266UL.toLong(), 0xbd1e9e439f105039UL.toLong(), 0xbd1e9e439f105039UL.toLong(),
        0xbd20de8b90075b8fUL.toLong(), 0x3d170cc16135783cUL.toLong(), 0x3d170cc16135783cUL.toLong(), 0x3cf178864d27543aUL.toLong(),
        0xbd248d301771c408UL.toLong(), 0xbd2e80a41811a396UL.toLong(), 0xbd2e80a41811a396UL.toLong(), 0x3d0a699688e85bf4UL.toLong(),
        0x3d0a699688e85bf4UL.toLong(), 0xbd2575545ca333f2UL.toLong(), 0x3d2a342c2af0003cUL.toLong(), 0x3d2a342c2af0003cUL.toLong(),
        0xbd1d0c57585fbe06UL.toLong(), 0x3d253935e85baac8UL.toLong(), 0x3d253935e85baac8UL.toLong(), 0x3d137c294d2f5668UL.toLong(),
        0x3d137c294d2f5668UL.toLong(), 0xbd269737c93373daUL.toLong(), 0x3d1f025b61c65e57UL.toLong(), 0x3d1f025b61c65e57UL.toLong(),
        0x3d2c5edaccf913dfUL.toLong(), 0x3d2c5edaccf913dfUL.toLong(), 0x3d147c5e768fa309UL.toLong(), 0x3d2d599e83368e91UL.toLong(),
        0x3d2d599e83368e91UL.toLong(), 0x3d1c827ae5d6704cUL.toLong(), 0x3d1c827ae5d6704cUL.toLong(), 0xbd2cfc4634f2a1eeUL.toLong(),
        0xbd2cfc4634f2a1eeUL.toLong(), 0x3cf502b7f526feaaUL.toLong(), 0x3cf502b7f526feaaUL.toLong(), 0xbd2980267c7e09e4UL.toLong(),
        0xbd2980267c7e09e4UL.toLong(), 0xbd288d5493faa639UL.toLong(), 0xbcdf1e7cf6d3a69cUL.toLong(), 0xbcdf1e7cf6d3a69cUL.toLong(),
        0xbd19e23f0dda40e4UL.toLong(), 0xbd19e23f0dda40e4UL.toLong(), 0x0000000000000000UL.toLong(), 0x0000000000000000UL.toLong(),
        0xbd10c76b999d2be8UL.toLong(), 0xbd23dc5b06e2f7d2UL.toLong(), 0xbd2aa0ba325a0c34UL.toLong(), 0x3d0111c05cf1d753UL.toLong(),
        0xbd2c167375bdfd28UL.toLong(), 0xbd197995d05a267dUL.toLong(), 0xbd1a68f247d82807UL.toLong(), 0xbd0e113e4fc93b7bUL.toLong(),
        0xbd25325d560d9e9bUL.toLong(), 0x3d2cc85ea5db4ed7UL.toLong(), 0xbd2c69063c5d1d1eUL.toLong(), 0x3cec1e8da99ded32UL.toLong(),
        0x3d23115c3abd47daUL.toLong(), 0xbd1390802bf768e5UL.toLong(), 0x3d2646d1c65aacd3UL.toLong(), 0xbd2dc068afe645e0UL.toLong(),
        0xbd2534d64fa10afdUL.toLong(), 0x3d21ef78ce2d07f2UL.toLong(), 0x3d2ca78e44389934UL.toLong(), 0x3d039d6ccb81b4a1UL.toLong(),
        0x3cc62fa8234b7289UL.toLong(), 0x3d25837954fdb678UL.toLong(), 0x3d2633e8e5697dc7UL.toLong(), 0x3d19cf8b2c3c2e78UL.toLong(),
        0xbd25118de59c21e1UL.toLong(), 0xbd1c661070914305UL.toLong(), 0xbd073d54aae92cd1UL.toLong(), 0x3d07f22858a0ff6fUL.toLong(),
        0xbd28724350562169UL.toLong(), 0xbd0c358d4eace1aaUL.toLong(), 0xbd2d4bc4595412b6UL.toLong(), 0xbcf1ec72c5962bd2UL.toLong(),
        0xbd2aff2af715b035UL.toLong(), 0x3cc212276041f430UL.toLong(), 0xbcca211565bb8e11UL.toLong(), 0x3d1bcbecca0cdf30UL.toLong(),
        0x3cf89cdb16ed4e91UL.toLong(), 0x3d27188b163ceae9UL.toLong(), 0xbd2c210e63a5f01cUL.toLong(), 0x3d2b9acdf7a51681UL.toLong(),
        0x3d2ca6ed5147bdb7UL.toLong(), 0x3d0a87deba46baeaUL.toLong(), 0x3d2a9cfa4a5004f4UL.toLong(), 0xbd28e27ad3213cb8UL.toLong(),
        0x3d116ecdb0f177c8UL.toLong(), 0x3d183b54b606bd5cUL.toLong(), 0x3d08e436ec90e09dUL.toLong(), 0xbd2f27ce0967d675UL.toLong(),
        0xbd2e20891b0ad8a4UL.toLong(), 0x3d2ebe708164c759UL.toLong(), 0x3d1fadedee5d40efUL.toLong(), 0xbd0a0b2a08a465dcUL.toLong(),
    )
    private val EX_INVLN2N = f(0x40671547652b82feUL); private val EX_NEGLN2HIN = f(0xbf762e42fefa0000UL); private val EX_NEGLN2LON = f(0xbd0cf79abc9e3b3aUL)
    private val EX_C = doubleArrayOf(f(0x3fdffffffffffdbdUL), f(0x3fc555555555543cUL), f(0x3fa55555cf172b91UL), f(0x3f81111167a4d017UL))   // __exp_data.poly[0..3] = C2..C5 (EXP_POLY_ORDER 5)
    private val EX_TAB = longArrayOf(
        0x0000000000000000UL.toLong(), 0x3ff0000000000000UL.toLong(), 0x3c9b3b4f1a88bf6eUL.toLong(), 0x3feff63da9fb3335UL.toLong(),
        0xbc7160139cd8dc5dUL.toLong(), 0x3fefec9a3e778061UL.toLong(), 0xbc905e7a108766d1UL.toLong(), 0x3fefe315e86e7f85UL.toLong(),
        0x3c8cd2523567f613UL.toLong(), 0x3fefd9b0d3158574UL.toLong(), 0xbc8bce8023f98efaUL.toLong(), 0x3fefd06b29ddf6deUL.toLong(),
        0x3c60f74e61e6c861UL.toLong(), 0x3fefc74518759bc8UL.toLong(), 0x3c90a3e45b33d399UL.toLong(), 0x3fefbe3ecac6f383UL.toLong(),
        0x3c979aa65d837b6dUL.toLong(), 0x3fefb5586cf9890fUL.toLong(), 0x3c8eb51a92fdeffcUL.toLong(), 0x3fefac922b7247f7UL.toLong(),
        0x3c3ebe3d702f9cd1UL.toLong(), 0x3fefa3ec32d3d1a2UL.toLong(), 0xbc6a033489906e0bUL.toLong(), 0x3fef9b66affed31bUL.toLong(),
        0xbc9556522a2fbd0eUL.toLong(), 0x3fef9301d0125b51UL.toLong(), 0xbc5080ef8c4eea55UL.toLong(), 0x3fef8abdc06c31ccUL.toLong(),
        0xbc91c923b9d5f416UL.toLong(), 0x3fef829aaea92de0UL.toLong(), 0x3c80d3e3e95c55afUL.toLong(), 0x3fef7a98c8a58e51UL.toLong(),
        0xbc801b15eaa59348UL.toLong(), 0x3fef72b83c7d517bUL.toLong(), 0xbc8f1ff055de323dUL.toLong(), 0x3fef6af9388c8deaUL.toLong(),
        0x3c8b898c3f1353bfUL.toLong(), 0x3fef635beb6fcb75UL.toLong(), 0xbc96d99c7611eb26UL.toLong(), 0x3fef5be084045cd4UL.toLong(),
        0x3c9aecf73e3a2f60UL.toLong(), 0x3fef54873168b9aaUL.toLong(), 0xbc8fe782cb86389dUL.toLong(), 0x3fef4d5022fcd91dUL.toLong(),
        0x3c8a6f4144a6c38dUL.toLong(), 0x3fef463b88628cd6UL.toLong(), 0x3c807a05b0e4047dUL.toLong(), 0x3fef3f49917ddc96UL.toLong(),
        0x3c968efde3a8a894UL.toLong(), 0x3fef387a6e756238UL.toLong(), 0x3c875e18f274487dUL.toLong(), 0x3fef31ce4fb2a63fUL.toLong(),
        0x3c80472b981fe7f2UL.toLong(), 0x3fef2b4565e27cddUL.toLong(), 0xbc96b87b3f71085eUL.toLong(), 0x3fef24dfe1f56381UL.toLong(),
        0x3c82f7e16d09ab31UL.toLong(), 0x3fef1e9df51fdee1UL.toLong(), 0xbc3d219b1a6fbffaUL.toLong(), 0x3fef187fd0dad990UL.toLong(),
        0x3c8b3782720c0ab4UL.toLong(), 0x3fef1285a6e4030bUL.toLong(), 0x3c6e149289cecb8fUL.toLong(), 0x3fef0cafa93e2f56UL.toLong(),
        0x3c834d754db0abb6UL.toLong(), 0x3fef06fe0a31b715UL.toLong(), 0x3c864201e2ac744cUL.toLong(), 0x3fef0170fc4cd831UL.toLong(),
        0x3c8fdd395dd3f84aUL.toLong(), 0x3feefc08b26416ffUL.toLong(), 0xbc86a3803b8e5b04UL.toLong(), 0x3feef6c55f929ff1UL.toLong(),
        0xbc924aedcc4b5068UL.toLong(), 0x3feef1a7373aa9cbUL.toLong(), 0xbc9907f81b512d8eUL.toLong(), 0x3feeecae6d05d866UL.toLong(),
        0xbc71d1e83e9436d2UL.toLong(), 0x3feee7db34e59ff7UL.toLong(), 0xbc991919b3ce1b15UL.toLong(), 0x3feee32dc313a8e5UL.toLong(),
        0x3c859f48a72a4c6dUL.toLong(), 0x3feedea64c123422UL.toLong(), 0xbc9312607a28698aUL.toLong(), 0x3feeda4504ac801cUL.toLong(),
        0xbc58a78f4817895bUL.toLong(), 0x3feed60a21f72e2aUL.toLong(), 0xbc7c2c9b67499a1bUL.toLong(), 0x3feed1f5d950a897UL.toLong(),
        0x3c4363ed60c2ac11UL.toLong(), 0x3feece086061892dUL.toLong(), 0x3c9666093b0664efUL.toLong(), 0x3feeca41ed1d0057UL.toLong(),
        0x3c6ecce1daa10379UL.toLong(), 0x3feec6a2b5c13cd0UL.toLong(), 0x3c93ff8e3f0f1230UL.toLong(), 0x3feec32af0d7d3deUL.toLong(),
        0x3c7690cebb7aafb0UL.toLong(), 0x3feebfdad5362a27UL.toLong(), 0x3c931dbdeb54e077UL.toLong(), 0x3feebcb299fddd0dUL.toLong(),
        0xbc8f94340071a38eUL.toLong(), 0x3feeb9b2769d2ca7UL.toLong(), 0xbc87deccdc93a349UL.toLong(), 0x3feeb6daa2cf6642UL.toLong(),
        0xbc78dec6bd0f385fUL.toLong(), 0x3feeb42b569d4f82UL.toLong(), 0xbc861246ec7b5cf6UL.toLong(), 0x3feeb1a4ca5d920fUL.toLong(),
        0x3c93350518fdd78eUL.toLong(), 0x3feeaf4736b527daUL.toLong(), 0x3c7b98b72f8a9b05UL.toLong(), 0x3feead12d497c7fdUL.toLong(),
        0x3c9063e1e21c5409UL.toLong(), 0x3feeab07dd485429UL.toLong(), 0x3c34c7855019c6eaUL.toLong(), 0x3feea9268a5946b7UL.toLong(),
        0x3c9432e62b64c035UL.toLong(), 0x3feea76f15ad2148UL.toLong(), 0xbc8ce44a6199769fUL.toLong(), 0x3feea5e1b976dc09UL.toLong(),
        0xbc8c33c53bef4da8UL.toLong(), 0x3feea47eb03a5585UL.toLong(), 0xbc845378892be9aeUL.toLong(), 0x3feea34634ccc320UL.toLong(),
        0xbc93cedd78565858UL.toLong(), 0x3feea23882552225UL.toLong(), 0x3c5710aa807e1964UL.toLong(), 0x3feea155d44ca973UL.toLong(),
        0xbc93b3efbf5e2228UL.toLong(), 0x3feea09e667f3bcdUL.toLong(), 0xbc6a12ad8734b982UL.toLong(), 0x3feea012750bdabfUL.toLong(),
        0xbc6367efb86da9eeUL.toLong(), 0x3fee9fb23c651a2fUL.toLong(), 0xbc80dc3d54e08851UL.toLong(), 0x3fee9f7df9519484UL.toLong(),
        0xbc781f647e5a3ecfUL.toLong(), 0x3fee9f75e8ec5f74UL.toLong(), 0xbc86ee4ac08b7db0UL.toLong(), 0x3fee9f9a48a58174UL.toLong(),
        0xbc8619321e55e68aUL.toLong(), 0x3fee9feb564267c9UL.toLong(), 0x3c909ccb5e09d4d3UL.toLong(), 0x3feea0694fde5d3fUL.toLong(),
        0xbc7b32dcb94da51dUL.toLong(), 0x3feea11473eb0187UL.toLong(), 0x3c94ecfd5467c06bUL.toLong(), 0x3feea1ed0130c132UL.toLong(),
        0x3c65ebe1abd66c55UL.toLong(), 0x3feea2f336cf4e62UL.toLong(), 0xbc88a1c52fb3cf42UL.toLong(), 0x3feea427543e1a12UL.toLong(),
        0xbc9369b6f13b3734UL.toLong(), 0x3feea589994cce13UL.toLong(), 0xbc805e843a19ff1eUL.toLong(), 0x3feea71a4623c7adUL.toLong(),
        0xbc94d450d872576eUL.toLong(), 0x3feea8d99b4492edUL.toLong(), 0x3c90ad675b0e8a00UL.toLong(), 0x3feeaac7d98a6699UL.toLong(),
        0x3c8db72fc1f0eab4UL.toLong(), 0x3feeace5422aa0dbUL.toLong(), 0xbc65b6609cc5e7ffUL.toLong(), 0x3feeaf3216b5448cUL.toLong(),
        0x3c7bf68359f35f44UL.toLong(), 0x3feeb1ae99157736UL.toLong(), 0xbc93091fa71e3d83UL.toLong(), 0x3feeb45b0b91ffc6UL.toLong(),
        0xbc5da9b88b6c1e29UL.toLong(), 0x3feeb737b0cdc5e5UL.toLong(), 0xbc6c23f97c90b959UL.toLong(), 0x3feeba44cbc8520fUL.toLong(),
        0xbc92434322f4f9aaUL.toLong(), 0x3feebd829fde4e50UL.toLong(), 0xbc85ca6cd7668e4bUL.toLong(), 0x3feec0f170ca07baUL.toLong(),
        0x3c71affc2b91ce27UL.toLong(), 0x3feec49182a3f090UL.toLong(), 0x3c6dd235e10a73bbUL.toLong(), 0x3feec86319e32323UL.toLong(),
        0xbc87c50422622263UL.toLong(), 0x3feecc667b5de565UL.toLong(), 0x3c8b1c86e3e231d5UL.toLong(), 0x3feed09bec4a2d33UL.toLong(),
        0xbc91bbd1d3bcbb15UL.toLong(), 0x3feed503b23e255dUL.toLong(), 0x3c90cc319cee31d2UL.toLong(), 0x3feed99e1330b358UL.toLong(),
        0x3c8469846e735ab3UL.toLong(), 0x3feede6b5579fdbfUL.toLong(), 0xbc82dfcd978e9db4UL.toLong(), 0x3feee36bbfd3f37aUL.toLong(),
        0x3c8c1a7792cb3387UL.toLong(), 0x3feee89f995ad3adUL.toLong(), 0xbc907b8f4ad1d9faUL.toLong(), 0x3feeee07298db666UL.toLong(),
        0xbc55c3d956dcaebaUL.toLong(), 0x3feef3a2b84f15fbUL.toLong(), 0xbc90a40e3da6f640UL.toLong(), 0x3feef9728de5593aUL.toLong(),
        0xbc68d6f438ad9334UL.toLong(), 0x3feeff76f2fb5e47UL.toLong(), 0xbc91eee26b588a35UL.toLong(), 0x3fef05b030a1064aUL.toLong(),
        0x3c74ffd70a5fddcdUL.toLong(), 0x3fef0c1e904bc1d2UL.toLong(), 0xbc91bdfbfa9298acUL.toLong(), 0x3fef12c25bd71e09UL.toLong(),
        0x3c736eae30af0cb3UL.toLong(), 0x3fef199bdd85529cUL.toLong(), 0x3c8ee3325c9ffd94UL.toLong(), 0x3fef20ab5fffd07aUL.toLong(),
        0x3c84e08fd10959acUL.toLong(), 0x3fef27f12e57d14bUL.toLong(), 0x3c63cdaf384e1a67UL.toLong(), 0x3fef2f6d9406e7b5UL.toLong(),
        0x3c676b2c6c921968UL.toLong(), 0x3fef3720dcef9069UL.toLong(), 0xbc808a1883ccb5d2UL.toLong(), 0x3fef3f0b555dc3faUL.toLong(),
        0xbc8fad5d3ffffa6fUL.toLong(), 0x3fef472d4a07897cUL.toLong(), 0xbc900dae3875a949UL.toLong(), 0x3fef4f87080d89f2UL.toLong(),
        0x3c74a385a63d07a7UL.toLong(), 0x3fef5818dcfba487UL.toLong(), 0xbc82919e2040220fUL.toLong(), 0x3fef60e316c98398UL.toLong(),
        0x3c8e5a50d5c192acUL.toLong(), 0x3fef69e603db3285UL.toLong(), 0x3c843a59ac016b4bUL.toLong(), 0x3fef7321f301b460UL.toLong(),
        0xbc82d52107b43e1fUL.toLong(), 0x3fef7c97337b9b5fUL.toLong(), 0xbc892ab93b470dc9UL.toLong(), 0x3fef864614f5a129UL.toLong(),
        0x3c74b604603a88d3UL.toLong(), 0x3fef902ee78b3ff6UL.toLong(), 0x3c83c5ec519d7271UL.toLong(), 0x3fef9a51fbc74c83UL.toLong(),
        0xbc8ff7128fd391f0UL.toLong(), 0x3fefa4afa2a490daUL.toLong(), 0xbc8dae98e223747dUL.toLong(), 0x3fefaf482d8e67f1UL.toLong(),
        0x3c8ec3bc41aa2008UL.toLong(), 0x3fefba1bee615a27UL.toLong(), 0x3c842b94c3a9eb32UL.toLong(), 0x3fefc52b376bba97UL.toLong(),
        0x3c8a64a931d185eeUL.toLong(), 0x3fefd0765b6e4540UL.toLong(), 0xbc8e37bae43be3edUL.toLong(), 0x3fefdbfdad9cbe14UL.toLong(),
        0x3c77893b4d91cd9dUL.toLong(), 0x3fefe7c1819e90d8UL.toLong(), 0x3c5305c14160cc89UL.toLong(), 0x3feff3c22b8f71f1UL.toLong(),
    )

    private fun top12(x: Double): Int = (x.toRawBits() ushr 52).toInt()
    private fun uge(a: Int, b: Int) = a.toUInt() >= b.toUInt()
    private fun ugt(a: Long, b: Long) = a.toULong() > b.toULong()
    private fun ult(a: Long, b: Long) = a.toULong() < b.toULong()

    /** C round(): to nearest, ties AWAY from zero (Kotlin's round() is ties-to-even). */
    private fun cRound(x: Double): Double {
        if (!(abs(x) < 4503599627370496.0)) return x            // |x| >= 2^52 (or NaN): already an integer
        val t = truncate(x)
        return if (abs(x - t) >= 0.5) t + (if (x < 0.0) -1.0 else 1.0) else t   // x - t is exact
    }

    private fun zeroInfNan(i: Long) = !ult(2 * i - 1, 2 * INF_BITS - 1)
    private fun isSignaling(x: Double) = ugt(2 * (x.toRawBits() xor 0x0008000000000000L), 2 * 0x7ff8000000000000L)

    /** 0 = not an integer, 1 = odd integer, 2 = even integer (iy = bits of a non-zero finite value). */
    private fun checkInt(iy: Long): Int {
        val e = ((iy ushr 52) and 0x7ff).toInt()
        if (e < 0x3ff) return 0
        if (e > 0x3ff + 52) return 2
        if (iy and ((1L shl (0x3ff + 52 - e)) - 1) != 0L) return 0
        if (iy and (1L shl (0x3ff + 52 - e)) != 0L) return 1
        return 2
    }

    private fun oflow(sign: Long): Double { val y = P2_769; return (if (sign != 0L) -y else y) * y }
    private fun uflow(sign: Long): Double { val y = P2_M767; return (if (sign != 0L) -y else y) * y }

    private fun specialCase(tmp: Double, sbits0: Long, ki: Long): Double {
        if (ki and 0x80000000L == 0L) {
            val scale = Double.fromBits(sbits0 - (1009L shl 52))  // k > 0: the exponent of scale may have overflowed by <= 460
            val y = fma(scale, tmp, scale)
            return y * P2_1009                                     // (the !__FP_FAST_FMA DBL_MAX case is not compiled on arm64)
        }
        val sbits = sbits0 + (1022L shl 52)                          // k < 0: care in the subnormal range
        val scale = Double.fromBits(sbits)
        var y = scale + scale * tmp      // NOT fused here: the compiled code forms scale*tmp once for this line and the next (measured, below)
        if (abs(y) < 1.0) {
            val one = if (y < 0.0) -1.0 else 1.0
            var lo = scale - y + scale * tmp
            val hi = one + y
            lo = one - hi + y + lo
            y = (hi + lo) - one
            if (y == 0.0) y = Double.fromBits(sbits and Long.MIN_VALUE)
        }
        return P2_M1022 * y
    }

    private fun expInline(x: Double, xtail: Double, signBias: Long): Double {
        var abstop = top12(x) and 0x7ff
        if (uge(abstop - top12(P2_M54), top12(512.0) - top12(P2_M54))) {
            if (uge(abstop - top12(P2_M54), 0x80000000.toInt())) {
                val one = 1.0 + x                                   // tiny x (0 is common)
                return if (signBias != 0L) -one else one
            }
            if (abstop >= top12(1024.0)) return if (x.toRawBits() < 0) uflow(signBias) else oflow(signBias)
            abstop = 0                                              // large x: special-cased below
        }
        val z = EX_INVLN2N * x
        val kd = cRound(z)                                          // roundtoint
        val ki = kd.toLong().toInt().toLong()                       // converttoint (int32), widened as C widens it to uint64
        var r = fma(kd, EX_NEGLN2LON, fma(kd, EX_NEGLN2HIN, x))                // x + kd*NegLn2hiN + kd*NegLn2loN, contracted
        r += xtail
        val idx = (2 * (ki and (N - 1).toLong())).toInt()
        val top = (ki + signBias) shl (52 - 7)
        val tail = Double.fromBits(EX_TAB[idx])
        val sbits = EX_TAB[idx + 1] + top
        val r2 = r * r
        val tmp = fma(r2 * r2, fma(r, EX_C[3], EX_C[2]), fma(r2, fma(r, EX_C[1], EX_C[0]), tail + r))   // tail + r + r2*(C2 + r*C3) + r2*r2*(C4 + r*C5)
        if (abstop == 0) return specialCase(tmp, sbits, ki)
        val scale = Double.fromBits(sbits)
        return fma(scale, tmp, scale)                                          // scale + scale*tmp
    }

    fun pow(x: Double, y: Double): Double {
        var signBias = 0L
        var ix = x.toRawBits()
        val iy = y.toRawBits()
        var topx = top12(x)
        val topy = top12(y)
        if (uge(topx - 0x001, 0x7ff - 0x001) || uge((topy and 0x7ff) - 0x3be, 0x43e - 0x3be)) {
            if (zeroInfNan(iy)) {
                if (2 * iy == 0L) return if (isSignaling(x)) x + y else 1.0
                if (ix == ONE_BITS) return if (isSignaling(y)) x + y else 1.0
                if (ugt(2 * ix, 2 * INF_BITS) || ugt(2 * iy, 2 * INF_BITS)) return x + y
                if (2 * ix == 2 * ONE_BITS) return 1.0
                if (ult(2 * ix, 2 * ONE_BITS) == (iy >= 0L)) return 0.0   // |x|<1 && y==inf or |x|>1 && y==-inf
                return y * y
            }
            if (zeroInfNan(ix)) {
                var x2 = x * x
                if (ix < 0L && checkInt(iy) == 1) { x2 = -x2; signBias = 1L }
                return if (iy < 0L) 1 / x2 else x2
            }
            if (ix < 0L) {                                          // finite x < 0
                val yint = checkInt(iy)
                if (yint == 0) return (x - x) / (x - x)             // __math_invalid
                if (yint == 1) signBias = SIGN_BIAS
                ix = ix and 0x7fffffffffffffffL
                topx = topx and 0x7ff
            }
            if (uge((topy and 0x7ff) - 0x3be, 0x43e - 0x3be)) {
                if (ix == ONE_BITS) return 1.0
                if ((topy and 0x7ff) < 0x3be) return if (ugt(ix, ONE_BITS)) 1.0 + y else 1.0 - y   // |y| < 2^-65
                return if (ugt(ix, ONE_BITS) == (topy < 0x800)) oflow(0L) else uflow(0L)
            }
            if (topx == 0) {                                        // subnormal x: normalize, exponent becomes negative
                ix = (x * P2_52).toRawBits()
                ix = ix and 0x7fffffffffffffffL
                ix -= 52L shl 52
            }
        }
        // ---- log_inline(ix): hi + lo = log(x) ----
        val tmpI = ix - OFF
        val i = ((tmpI ushr (52 - 7)) and (N - 1).toLong()).toInt()
        val k = (tmpI shr 52).toInt()                               // arithmetic shift
        val iz = ix - (tmpI and (0xfffL shl 52))
        val z = Double.fromBits(iz)
        val kd = k.toDouble()
        val invc = Double.fromBits(PL_INVC[i]); val logc = Double.fromBits(PL_LOGC[i]); val logctail = Double.fromBits(PL_LOGCTAIL[i])
        val r = fma(z, invc, -1.0)
        val t1 = fma(kd, PL_LN2HI, logc)                                        // kd*Ln2hi + logc
        val t2 = t1 + r
        val lo1 = fma(kd, PL_LN2LO, logctail)                                   // kd*Ln2lo + logctail
        val lo2 = t1 - t2 + r
        val ar = PL_A[0] * r
        val ar2 = r * ar
        val ar3 = r * ar2
        val hi0 = t2 + ar2
        val lo3 = fma(ar, r, -ar2)
        val lo4 = t2 - hi0 + ar2
        val p = ar3 * fma(ar2, fma(ar2, fma(r, PL_A[6], PL_A[5]), fma(r, PL_A[4], PL_A[3])), fma(r, PL_A[2], PL_A[1]))   // ar3*(A1 + r*A2 + ar2*(A3 + r*A4 + ar2*(A5 + r*A6)))
        val lo0 = lo1 + lo2 + lo3 + lo4 + p
        val hi = hi0 + lo0
        val lo = hi0 - hi + lo0
        // ---- y * log(x), then exp ----
        val ehi = y * hi
        val elo = fma(y, lo, fma(y, hi, -ehi))                                 // y*lo + fma(y, hi, -ehi)
        return expInline(ehi, elo, signBias)
    }
}
