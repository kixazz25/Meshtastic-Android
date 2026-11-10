package com.grouptrack.shared.text

import platform.posix.fma

/**
 * MATHTILE-2026-11-10 (2.8 step 16a): ANDROID's own natural log, in Kotlin, for the iPad (GtMath.ln's iOS actual). On Android GtMath.ln
 * IS java.lang.Math.log -- unchanged.
 * WHY: MATHTILE 11-10 -- Apple's log differs from the Droid in the last bit on 647 of 400,000 real tile-math inputs (tan + 1/cos of
 * track / trail latitudes), enough to move a tile row's value (Y) on 25,040 with tan's differences.
 * WHAT ANDROID RUNS: Arm's optimized-routines log (bionic's arm64 libm -- the same code glibc uses on 64-bit Arm). Proven 11-10: glibc
 * 2.35's log on aarch64 = the Droid on 400,000 / 400,000 (fdlibm's differed on 9,704). This is that algorithm (math/log.c +
 * math/log_data.c, Copyright (c) 2018-2026 Arm Limited, SPDX-License-Identifier: MIT OR Apache-2.0 WITH LLVM-exception) as built for
 * arm64: LOG_TABLE_BITS = 7 (N = 128), LOG_POLY_ORDER = 6, LOG_POLY1_ORDER = 12, HAVE_FAST_FMA = 1 (r = fma(z, invc, -1)), and clang's
 * default contraction (-ffp-contract=on) -- every a*b + c INSIDE ONE C expression is one fused multiply-add, written here as fma with
 * the C expression beside it (as GtMathPow, proven the same way 11-10). Tables: log_data.c's own hex literals as raw bits.
 * NOT REACHED by GroupTrack's tile inputs and so not proven on the Droid: the near-1 branch (|x - 1| < ~0.065), subnormal / zero /
 * negative / Inf / NaN input -- ported as written.
 */
internal object GtMathLog {
    private fun f(u: ULong): Double = Double.fromBits(u.toLong())
    private const val N = 128
    private const val OFF = 0x3fe6000000000000UL
    private val LN2HI = f(0x3fe62e42fefa3800UL)
    private val LN2LO = f(0x3d2ef35793c76730UL)
    private val A = doubleArrayOf(f(0xbfe0000000000001UL), f(0x3fd555555551305bUL), f(0xbfcfffffffeb4590UL), f(0x3fc999b324f10111UL), f(0xbfc55575e506c89fUL))
    private val B = doubleArrayOf(f(0xbfe0000000000000UL), f(0x3fd5555555555577UL), f(0xbfcffffffffffdcbUL), f(0x3fc999999995dd0cUL), f(0xbfc55555556745a7UL), f(0x3fc24924a344de30UL), f(0xbfbfffffa4423d65UL), f(0x3fbc7184282ad6caUL), f(0xbfb999eb43b068ffUL), f(0x3fb78182f7afd085UL), f(0xbfb5521375d145cdUL))
    private val INVC = longArrayOf(
        0x3ff734f0c3e0de9fUL.toLong(), 0x3ff713786a2ce91fUL.toLong(), 0x3ff6f26008fab5a0UL.toLong(), 0x3ff6d1a61f138c7dUL.toLong(),
        0x3ff6b1490bc5b4d1UL.toLong(), 0x3ff69147332f0cbaUL.toLong(), 0x3ff6719f18224223UL.toLong(), 0x3ff6524f99a51ed9UL.toLong(),
        0x3ff63356aa8f24c4UL.toLong(), 0x3ff614b36b9ddc14UL.toLong(), 0x3ff5f66452c65c4cUL.toLong(), 0x3ff5d867b5912c4fUL.toLong(),
        0x3ff5babccb5b90deUL.toLong(), 0x3ff59d61f2d91a78UL.toLong(), 0x3ff5805612465687UL.toLong(), 0x3ff56397cee76bd3UL.toLong(),
        0x3ff54725e2a77f93UL.toLong(), 0x3ff52aff42064583UL.toLong(), 0x3ff50f22dbb2bddfUL.toLong(), 0x3ff4f38f4734ded7UL.toLong(),
        0x3ff4d843cfde2840UL.toLong(), 0x3ff4bd3ec078a3c8UL.toLong(), 0x3ff4a27fc3e0258aUL.toLong(), 0x3ff4880524d48434UL.toLong(),
        0x3ff46dce1b192d0bUL.toLong(), 0x3ff453d9d3391854UL.toLong(), 0x3ff43a2744b4845aUL.toLong(), 0x3ff420b54115f8fbUL.toLong(),
        0x3ff40782da3ef4b1UL.toLong(), 0x3ff3ee8f5d57fe8fUL.toLong(), 0x3ff3d5d9a00b4ce9UL.toLong(), 0x3ff3bd60c010c12bUL.toLong(),
        0x3ff3a5242b75dab8UL.toLong(), 0x3ff38d22cd9fd002UL.toLong(), 0x3ff3755bc5847a1cUL.toLong(), 0x3ff35dce49ad36e2UL.toLong(),
        0x3ff34679984dd440UL.toLong(), 0x3ff32f5cceffcb24UL.toLong(), 0x3ff3187775a10d49UL.toLong(), 0x3ff301c8373e3990UL.toLong(),
        0x3ff2eb4ebb95f841UL.toLong(), 0x3ff2d50a0219a9d1UL.toLong(), 0x3ff2bef9a8b7fd2aUL.toLong(), 0x3ff2a91c7a0c1babUL.toLong(),
        0x3ff293726014b530UL.toLong(), 0x3ff27dfa5757a1f5UL.toLong(), 0x3ff268b39b1d3bbfUL.toLong(), 0x3ff2539d838ff5bdUL.toLong(),
        0x3ff23eb7aac9083bUL.toLong(), 0x3ff22a012ba940b6UL.toLong(), 0x3ff2157996cc4132UL.toLong(), 0x3ff201201dd2fc9bUL.toLong(),
        0x3ff1ecf4494d480bUL.toLong(), 0x3ff1d8f5528f6569UL.toLong(), 0x3ff1c52311577e7cUL.toLong(), 0x3ff1b17c74cb26e9UL.toLong(),
        0x3ff19e010c2c1ab6UL.toLong(), 0x3ff18ab07bb670bdUL.toLong(), 0x3ff1778a25efbcb6UL.toLong(), 0x3ff1648d354c31daUL.toLong(),
        0x3ff151b990275fddUL.toLong(), 0x3ff13f0ea432d24cUL.toLong(), 0x3ff12c8b7210f9daUL.toLong(), 0x3ff11a3028ecb531UL.toLong(),
        0x3ff107fbda8434afUL.toLong(), 0x3ff0f5ee0f4e6bb3UL.toLong(), 0x3ff0e4065d2a9fceUL.toLong(), 0x3ff0d244632ca521UL.toLong(),
        0x3ff0c0a77ce2981aUL.toLong(), 0x3ff0af2f83c636d1UL.toLong(), 0x3ff09ddb98a01339UL.toLong(), 0x3ff08cabaf52e7dfUL.toLong(),
        0x3ff07b9f2f4e28fbUL.toLong(), 0x3ff06ab58c358f19UL.toLong(), 0x3ff059eea5ecf92cUL.toLong(), 0x3ff04949cdd12c90UL.toLong(),
        0x3ff038c6c6f0ada9UL.toLong(), 0x3ff02865137932a9UL.toLong(), 0x3ff0182427ea7348UL.toLong(), 0x3ff008040614b195UL.toLong(),
        0x3fefe01ff726fa1aUL.toLong(), 0x3fefa11cc261ea74UL.toLong(), 0x3fef6310b081992eUL.toLong(), 0x3fef25f63ceeadcdUL.toLong(),
        0x3feee9c8039113e7UL.toLong(), 0x3feeae8078cbb1abUL.toLong(), 0x3fee741aa29d0c9bUL.toLong(), 0x3fee3a91830a99b5UL.toLong(),
        0x3fee01e009609a56UL.toLong(), 0x3fedca01e577bb98UL.toLong(), 0x3fed92f20b7c9103UL.toLong(), 0x3fed5cac66fb5cceUL.toLong(),
        0x3fed272caa5ede9dUL.toLong(), 0x3fecf26e3e6b2ccdUL.toLong(), 0x3fecbe6da2a77902UL.toLong(), 0x3fec8b266d37086dUL.toLong(),
        0x3fec5894bd5d5804UL.toLong(), 0x3fec26b533bb9f8cUL.toLong(), 0x3febf583eeece73fUL.toLong(), 0x3febc4fd75db96c1UL.toLong(),
        0x3feb951e0c864a28UL.toLong(), 0x3feb65e2c5ef3e2cUL.toLong(), 0x3feb374867c9888bUL.toLong(), 0x3feb094b211d304aUL.toLong(),
        0x3feadbe885f2ef7eUL.toLong(), 0x3feaaf1d31603da2UL.toLong(), 0x3fea82e63fd358a7UL.toLong(), 0x3fea5740ef09738bUL.toLong(),
        0x3fea2c2a90ab4b27UL.toLong(), 0x3fea01a01393f2d1UL.toLong(), 0x3fe9d79f24db3c1bUL.toLong(), 0x3fe9ae2505c7b190UL.toLong(),
        0x3fe9852ef297ce2fUL.toLong(), 0x3fe95cbaeea44b75UL.toLong(), 0x3fe934c69de74838UL.toLong(), 0x3fe90d4f2f6752e6UL.toLong(),
        0x3fe8e6528effd79dUL.toLong(), 0x3fe8bfce9fcc007cUL.toLong(), 0x3fe899c0dabec30eUL.toLong(), 0x3fe87427aa2317fbUL.toLong(),
        0x3fe84f00acb39a08UL.toLong(), 0x3fe82a49e8653e55UL.toLong(), 0x3fe8060195f40260UL.toLong(), 0x3fe7e22563e0a329UL.toLong(),
        0x3fe7beb377dcb5adUL.toLong(), 0x3fe79baa679725c2UL.toLong(), 0x3fe77907f2170657UL.toLong(), 0x3fe756cadbd6130cUL.toLong())
    private val LOGC = longArrayOf(
        0xbfd7cc7f79e69000UL.toLong(), 0xbfd76feec20d0000UL.toLong(), 0xbfd713e31351e000UL.toLong(), 0xbfd6b85b38287800UL.toLong(),
        0xbfd65d5590807800UL.toLong(), 0xbfd602d076180000UL.toLong(), 0xbfd5a8ca86909000UL.toLong(), 0xbfd54f4356035000UL.toLong(),
        0xbfd4f637c36b4000UL.toLong(), 0xbfd49da7fda85000UL.toLong(), 0xbfd445923989a800UL.toLong(), 0xbfd3edf439b0b800UL.toLong(),
        0xbfd396ce448f7000UL.toLong(), 0xbfd3401e17bda000UL.toLong(), 0xbfd2e9e2ef468000UL.toLong(), 0xbfd2941b3830e000UL.toLong(),
        0xbfd23ec58cda8800UL.toLong(), 0xbfd1e9e129279000UL.toLong(), 0xbfd1956d2b48f800UL.toLong(), 0xbfd141679ab9f800UL.toLong(),
        0xbfd0edd094ef9800UL.toLong(), 0xbfd09aa518db1000UL.toLong(), 0xbfd047e65263b800UL.toLong(), 0xbfcfeb224586f000UL.toLong(),
        0xbfcf474a7517b000UL.toLong(), 0xbfcea4443d103000UL.toLong(), 0xbfce020d44e9b000UL.toLong(), 0xbfcd60a22977f000UL.toLong(),
        0xbfccc00104959000UL.toLong(), 0xbfcc202956891000UL.toLong(), 0xbfcb81178d811000UL.toLong(), 0xbfcae2c9ccd3d000UL.toLong(),
        0xbfca45402e129000UL.toLong(), 0xbfc9a877681df000UL.toLong(), 0xbfc90c6d69483000UL.toLong(), 0xbfc87120a645c000UL.toLong(),
        0xbfc7d68fb4143000UL.toLong(), 0xbfc73cb83c627000UL.toLong(), 0xbfc6a39a9b376000UL.toLong(), 0xbfc60b3154b7a000UL.toLong(),
        0xbfc5737d76243000UL.toLong(), 0xbfc4dc7b8fc23000UL.toLong(), 0xbfc4462c51d20000UL.toLong(), 0xbfc3b08abc830000UL.toLong(),
        0xbfc31b996b490000UL.toLong(), 0xbfc2875490a44000UL.toLong(), 0xbfc1f3b9f879a000UL.toLong(), 0xbfc160c8252ca000UL.toLong(),
        0xbfc0ce7f57f72000UL.toLong(), 0xbfc03cdc49fea000UL.toLong(), 0xbfbf57bdbc4b8000UL.toLong(), 0xbfbe370896404000UL.toLong(),
        0xbfbd17983ef94000UL.toLong(), 0xbfbbf9674ed8a000UL.toLong(), 0xbfbadc79202f6000UL.toLong(), 0xbfb9c0c3e7288000UL.toLong(),
        0xbfb8a646b372c000UL.toLong(), 0xbfb78d01b3ac0000UL.toLong(), 0xbfb674f145380000UL.toLong(), 0xbfb55e0e6d878000UL.toLong(),
        0xbfb4485cdea1e000UL.toLong(), 0xbfb333d94d6aa000UL.toLong(), 0xbfb22079f8c56000UL.toLong(), 0xbfb10e4698622000UL.toLong(),
        0xbfaffa6c6ad20000UL.toLong(), 0xbfadda8d4a774000UL.toLong(), 0xbfabbcece4850000UL.toLong(), 0xbfa9a1894012c000UL.toLong(),
        0xbfa788583302c000UL.toLong(), 0xbfa5715e67d68000UL.toLong(), 0xbfa35c8a49658000UL.toLong(), 0xbfa149e364154000UL.toLong(),
        0xbf9e72c082eb8000UL.toLong(), 0xbf9a55f152528000UL.toLong(), 0xbf963d62cf818000UL.toLong(), 0xbf9228fb8caa0000UL.toLong(),
        0xbf8c317b20f90000UL.toLong(), 0xbf8419355daa0000UL.toLong(), 0xbf781203c2ec0000UL.toLong(), 0xbf60040979240000UL.toLong(),
        0x3f6feff384900000UL.toLong(), 0x3f87dc41353d0000UL.toLong(), 0x3f93cea3c4c28000UL.toLong(), 0x3f9b9fc114890000UL.toLong(),
        0x3fa1b0d8ce110000UL.toLong(), 0x3fa58a5bd001c000UL.toLong(), 0x3fa95c8340d88000UL.toLong(), 0x3fad276aef578000UL.toLong(),
        0x3fb07598e598c000UL.toLong(), 0x3fb253f5e30d2000UL.toLong(), 0x3fb42edd8b380000UL.toLong(), 0x3fb606598757c000UL.toLong(),
        0x3fb7da76356a0000UL.toLong(), 0x3fb9ab434e1c6000UL.toLong(), 0x3fbb78c7bb0d6000UL.toLong(), 0x3fbd431332e72000UL.toLong(),
        0x3fbf0a3171de6000UL.toLong(), 0x3fc067152b914000UL.toLong(), 0x3fc147858292b000UL.toLong(), 0x3fc2266ecdca3000UL.toLong(),
        0x3fc303d7a6c55000UL.toLong(), 0x3fc3dfc33c331000UL.toLong(), 0x3fc4ba366b7a8000UL.toLong(), 0x3fc5933928d1f000UL.toLong(),
        0x3fc66acd2418f000UL.toLong(), 0x3fc740f8ec669000UL.toLong(), 0x3fc815c0f51af000UL.toLong(), 0x3fc8e92954f68000UL.toLong(),
        0x3fc9bb3602f84000UL.toLong(), 0x3fca8bed1c2c0000UL.toLong(), 0x3fcb5b515c01d000UL.toLong(), 0x3fcc2967ccbcc000UL.toLong(),
        0x3fccf635d5486000UL.toLong(), 0x3fcdc1bd3446c000UL.toLong(), 0x3fce8c01b8cfe000UL.toLong(), 0x3fcf5509c0179000UL.toLong(),
        0x3fd00e6c121fb800UL.toLong(), 0x3fd071b80e93d000UL.toLong(), 0x3fd0d46b9e867000UL.toLong(), 0x3fd13687334bd000UL.toLong(),
        0x3fd1980d67234800UL.toLong(), 0x3fd1f8ffe0cc8000UL.toLong(), 0x3fd2595fd7636800UL.toLong(), 0x3fd2b9300914a800UL.toLong(),
        0x3fd3187210436000UL.toLong(), 0x3fd377266dec1800UL.toLong(), 0x3fd3d54ffbaf3000UL.toLong(), 0x3fd432eee32fe000UL.toLong())
    private val P2_27 = Double.fromBits((27L + 1023L) shl 52)
    private val P2_52 = Double.fromBits((52L + 1023L) shl 52)
    private const val LO = 0x3fee000000000000UL        // asuint64(1.0 - 0x1p-4)
    private const val HI = 0x3ff1090000000000UL        // asuint64(1.0 + 0x1.09p-4)

    fun log(x: Double): Double {
        var ix = x.toRawBits().toULong()
        val top = (ix shr 48).toUInt()
        if (ix - LO < HI - LO) {
            // close to 1.0
            if (ix == 1.0.toRawBits().toULong()) return 0.0
            val r = x - 1.0
            val r2 = r * r
            val r3 = r * r2
            // y = r3 * (B[1] + r*B[2] + r2*B[3] + r3*(B[4] + r*B[5] + r2*B[6] + r3*(B[7] + r*B[8] + r2*B[9] + r3*B[10])))
            val in2 = fma(r3, B[10], fma(r2, B[9], fma(r, B[8], B[7])))
            val in1 = fma(r3, in2, fma(r2, B[6], fma(r, B[5], B[4])))
            var y = r3 * fma(r3, in1, fma(r2, B[3], fma(r, B[2], B[1])))
            // N > 64: w = r * 0x1p27; rhi = r + w - w; rlo = r - rhi; w = rhi * rhi * B[0]; hi = r + w; lo = r - hi + w;
            var w = r * P2_27
            val rhi = r + w - w
            val rlo = r - rhi
            w = rhi * rhi * B[0]
            val hi = r + w
            var lo = r - hi + w
            lo = fma(B[0] * rlo, rhi + r, lo)                   // lo += B[0] * rlo * (rhi + r)
            y += lo
            y += hi
            return y
        }
        if (top - 0x0010u >= 0x7ff0u - 0x0010u) {
            // x < 0x1p-1022 or inf or nan
            if (ix * 2u == 0uL) return -1.0 / 0.0                                 // __math_divzero(1)
            if (ix == Double.POSITIVE_INFINITY.toRawBits().toULong()) return x
            if ((top and 0x8000u) != 0u || (top and 0x7ff0u) == 0x7ff0u) return (x - x) / (x - x)   // __math_invalid(x)
            ix = (x * P2_52).toRawBits().toULong()
            ix -= 52uL shl 52
        }
        val tmp = ix - OFF
        val i = ((tmp shr (52 - 7)) % N.toULong()).toInt()
        val k = (tmp.toLong() shr 52).toInt()                                 // arithmetic shift
        val iz = ix - (tmp and (0xfffuL shl 52))
        val invc = Double.fromBits(INVC[i]); val logc = Double.fromBits(LOGC[i])
        val z = Double.fromBits(iz.toLong())
        val r = fma(z, invc, -1.0)
        val kd = k.toDouble()
        val w = fma(kd, LN2HI, logc)                                          // w = kd * Ln2hi + logc
        val hi = w + r
        val lo = fma(kd, LN2LO, w - hi + r)                                    // lo = w - hi + r + kd * Ln2lo
        val r2 = r * r
        // y = lo + r2 * A[0] + r * r2 * (A[1] + r * A[2] + r2 * (A[3] + r * A[4])) + hi
        val p = fma(r2, fma(r, A[4], A[3]), fma(r, A[2], A[1]))
        return fma(r * r2, p, fma(r2, A[0], lo)) + hi
    }
}
