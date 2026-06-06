<?php
/**
 * Convoy Tracker REST API
 * Single-file PHP API — handles all endpoints
 *
 * Endpoints:
 *   POST   /users/register       — Google Sign-In, create or update user
 *   POST   /rides                — Create a new ride
 *   POST   /rides/{id}/invite    — Generate invite token for a ride
 *   GET    /ride/{token}         — Get ride JSON by invite token (rider enrollment)
 *   GET    /rides                — List rides for authenticated organizer
 */

header('Content-Type: application/json');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: GET, POST, OPTIONS');
header('Access-Control-Allow-Headers: Content-Type, Authorization');

if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(200);
    exit();
}

// ── Database config ──────────────────────────────────────────────────────────
define('DB_HOST', 'convoy-tracker-db.cudtjxrtdbql.us-east-1.rds.amazonaws.com');
define('DB_PORT', '3306');
define('DB_NAME', 'convoy_tracker');
define('DB_USER', 'convoy_admin');
define('DB_PASS', 'Sports256!!!'); // Replace with your RDS password

// ── Connect ──────────────────────────────────────────────────────────────────
function getDb() {
    static $pdo = null;
    if ($pdo === null) {
        try {
            $dsn = "mysql:host=" . DB_HOST . ";port=" . DB_PORT . ";dbname=" . DB_NAME . ";charset=utf8mb4";
            $pdo = new PDO($dsn, DB_USER, DB_PASS, [
                PDO::ATTR_ERRMODE            => PDO::ERRMODE_EXCEPTION,
                PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
            ]);
        } catch (PDOException $e) {
            error('Database connection failed: ' . $e->getMessage(), 500);
        }
    }
    return $pdo;
}

// ── Helpers ──────────────────────────────────────────────────────────────────
function error($message, $code = 400) {
    http_response_code($code);
    echo json_encode(['error' => $message]);
    exit();
}

function success($data, $code = 200) {
    http_response_code($code);
    echo json_encode($data);
    exit();
}

function getBody() {
    return json_decode(file_get_contents('php://input'), true) ?? [];
}

function generateUUID() {
    return sprintf('%04x%04x-%04x-%04x-%04x-%04x%04x%04x',
        mt_rand(0, 0xffff), mt_rand(0, 0xffff),
        mt_rand(0, 0xffff),
        mt_rand(0, 0x0fff) | 0x4000,
        mt_rand(0, 0x3fff) | 0x8000,
        mt_rand(0, 0xffff), mt_rand(0, 0xffff), mt_rand(0, 0xffff)
    );
}

function generateToken() {
    return bin2hex(random_bytes(32));
}

// ── Router ───────────────────────────────────────────────────────────────────
$method = $_SERVER['REQUEST_METHOD'];
$path = trim(str_replace("convoy_api.php", "", parse_url($_SERVER["REQUEST_URI"], PHP_URL_PATH)), "/");
$parts  = explode('/', $path);

// POST /users/register
if ($method === 'POST' && $parts[0] === 'users' && ($parts[1] ?? '') === 'register') {
    $body = getBody();
    $googleId  = $body['google_id']  ?? null;
    $email     = $body['email']      ?? null;
    $firstName = $body['first_name'] ?? null;
    $lastName  = $body['last_name']  ?? null;
    $cell      = $body['cell']       ?? '';

    if (!$googleId || !$email || !$firstName || !$lastName) {
        error('Missing required fields: google_id, email, first_name, last_name');
    }

    $db = getDb();

    // Check if user exists
    $stmt = $db->prepare('SELECT user_id, first_name, last_name, email FROM users WHERE google_id = ?');
    $stmt->execute([$googleId]);
    $user = $stmt->fetch();

    if ($user) {
        // Update existing user
        $db->prepare('UPDATE users SET email=?, first_name=?, last_name=?, cell=? WHERE google_id=?')
           ->execute([$email, $firstName, $lastName, $cell, $googleId]);
        success(['user_id' => $user['user_id'], 'status' => 'updated']);
    } else {
        // Create new user
        $userId = generateUUID();
        $db->prepare('INSERT INTO users (user_id, google_id, first_name, last_name, cell, email) VALUES (?,?,?,?,?,?)')
           ->execute([$userId, $googleId, $firstName, $lastName, $cell, $email]);
        success(['user_id' => $userId, 'status' => 'created'], 201);
    }
}

// POST /rides — create a ride
if ($method === 'POST' && $parts[0] === 'rides' && count($parts) === 1) {
    $body = getBody();
    $rideName    = $body['ride_name']    ?? null;
    $rideDate    = $body['ride_date']    ?? null;
    $channelName = $body['channel_name'] ?? null;
    $channelPsk  = $body['channel_psk']  ?? null;
    $organizerId = $body['organizer_id'] ?? null;

    if (!$rideName || !$rideDate || !$channelName || !$channelPsk || !$organizerId) {
        error('Missing required fields: ride_name, ride_date, channel_name, channel_psk, organizer_id');
    }

    $db = getDb();
    $rideId = generateUUID();

    $db->prepare('INSERT INTO rides (ride_id, ride_name, ride_date, channel_name, channel_psk, organizer_id) VALUES (?,?,?,?,?,?)')
       ->execute([$rideId, $rideName, $rideDate, $channelName, $channelPsk, $organizerId]);

    // Auto-enroll organizer
    $enrollId = generateUUID();
    $db->prepare('INSERT INTO enrollments (enrollment_id, ride_id, user_id, role) VALUES (?,?,?,?)')
       ->execute([$enrollId, $rideId, $organizerId, 'ORGANIZER']);

    success(['ride_id' => $rideId, 'status' => 'created'], 201);
}

// POST /rides/{id}/invite — generate invite token
if ($method === 'POST' && $parts[0] === 'rides' && ($parts[2] ?? '') === 'invite') {
    $rideId      = $parts[1] ?? null;
    $body        = getBody();
    $invitedBy   = $body['organizer_id'] ?? null;

    if (!$rideId || !$invitedBy) {
        error('Missing ride_id or organizer_id');
    }

    $db = getDb();

    // Verify organizer owns this ride
    $stmt = $db->prepare('SELECT ride_id FROM rides WHERE ride_id = ? AND organizer_id = ?');
    $stmt->execute([$rideId, $invitedBy]);
    if (!$stmt->fetch()) {
        error('Ride not found or not authorized', 403);
    }

    $token    = generateToken();
    $inviteId = generateUUID();

    $db->prepare('INSERT INTO invites (invite_id, ride_id, invited_by, invite_token) VALUES (?,?,?,?)')
       ->execute([$inviteId, $rideId, $invitedBy, $token]);

    $inviteUrl = 'convoy://join/' . $token;
    success(['invite_token' => $token, 'invite_url' => $inviteUrl, 'status' => 'created'], 201);
}

// GET /ride/{token} — rider claims invite and gets ride JSON
if ($method === 'GET' && $parts[0] === 'ride' && isset($parts[1])) {
    $token = $parts[1];
    $db    = getDb();

    $stmt = $db->prepare('
        SELECT i.invite_id, i.claimed_by, i.ride_id,
               r.ride_name, r.ride_date, r.channel_name, r.channel_psk,
               u.first_name, u.last_name, u.email
        FROM invites i
        JOIN rides r ON r.ride_id = i.ride_id
        JOIN users u ON u.user_id = r.organizer_id
        WHERE i.invite_token = ?
    ');
    $stmt->execute([$token]);
    $row = $stmt->fetch();

    if (!$row) {
        error('Invalid or expired invite token', 404);
    }

    // Return ride JSON — format matches Android ConvoyEventConfig
    success([
        'convoyDocType'      => 'convoy_ride',
        'eventId'            => $row['ride_id'],
        'eventName'          => $row['ride_name'],
        'eventDate'          => $row['ride_date'],
        'channelName'        => $row['channel_name'],
        'channelPsk'         => $row['channel_psk'],
        'organizerFirstName' => $row['first_name'],
        'organizerLastName'  => $row['last_name'],
        'organizerEmail'     => $row['email'],
        'createdDate'        => date('Y-m-d\TH:i:s\Z'),
        'expirationDate'     => date('Y-m-d', strtotime($row['ride_date'] . ' +30 days')),
    ]);
}

// GET /rides — list rides for organizer
if ($method === 'GET' && $parts[0] === 'rides') {
    $organizerId = $_GET['organizer_id'] ?? null;
    if (!$organizerId) error('Missing organizer_id');

    $db   = getDb();
    $stmt = $db->prepare('SELECT ride_id, ride_name, ride_date, channel_name, created_at FROM rides WHERE organizer_id = ? ORDER BY ride_date DESC');
    $stmt->execute([$organizerId]);
    success(['rides' => $stmt->fetchAll()]);
}


// ============================================================
// POST /follows � rider follows an organizer
// Synchronous: writes follow record, creates invited enrollments
// on all organizer open public rides, sends invite emails.
// Body: { follower_id, following_id }
// ============================================================
if ($method === 'POST' && $parts[0] === 'follows' && count($parts) === 1) {
    $body       = getBody();
    $followerId  = $body['follower_id']  ?? null;
    $followingId = $body['following_id'] ?? null;
    if (!$followerId || !$followingId) error('Missing follower_id or following_id');

    $db = getDb();

    // 1. Check not already following
    $chk = $db->prepare('SELECT follow_id FROM follows WHERE follower_id = ? AND following_id = ? AND status = ?');
    $chk->execute([$followerId, $followingId, 'active']);
    if ($chk->fetch()) {
        success(['message' => 'Already following', 'enrollments_created' => 0]);
    }

    // 2. Write follows record (upsert � may exist as inactive)
    $upsert = $db->prepare('
        INSERT INTO follows (follow_id, follower_id, following_id, status)
        VALUES (UUID(), ?, ?, ?)
        ON DUPLICATE KEY UPDATE status = ?
    ');
    $upsert->execute([$followerId, $followingId, 'active', 'active']);

    // 3. Get organizer open public rides
    $rides = $db->prepare('
        SELECT ride_id, ride_name, ride_date, start_time
        FROM rides
        WHERE organizer_id = ?
          AND ride_status = ?
          AND is_public = 1
    ');
    $rides->execute([$followingId, 'open']);
    $openRides = $rides->fetchAll();

    // 4. Get follower email for notifications
    $userStmt = $db->prepare('SELECT email, first_name FROM users WHERE user_id = ?');
    $userStmt->execute([$followerId]);
    $follower = $userStmt->fetch();

    // 5. Get organizer name
    $orgStmt = $db->prepare('SELECT first_name, last_name, email FROM users WHERE user_id = ?');
    $orgStmt->execute([$followingId]);
    $organizer = $orgStmt->fetch();

    $enrolled = 0;
    foreach ($openRides as $ride) {
        // Skip if enrollment already exists for this rider on this ride
        $exists = $db->prepare('SELECT enrollment_id FROM enrollments WHERE ride_id = ? AND user_id = ?');
        $exists->execute([$ride['ride_id'], $followerId]);
        if ($exists->fetch()) continue;

        // Create invited enrollment
        $enroll = $db->prepare('
            INSERT INTO enrollments (enrollment_id, ride_id, user_id, status)
            VALUES (UUID(), ?, ?, ?)
        ');
        $enroll->execute([$ride['ride_id'], $followerId, 'invited']);
        $enrolled++;

        // Send invite email � stub, replace with real mailer in Phase C
        // mail($follower['email'],
        //     'You are invited: ' . $ride['ride_name'],
        //     'GroupTrack invite from ' . $organizer['first_name'] . ' ' . $organizer['last_name']
        // );
    }

    success([
        'message'             => 'Follow created',
        'enrollments_created' => $enrolled,
        'rides_invited'       => array_column($openRides, 'ride_id')
    ]);
}

// ============================================================
// DELETE /follows � rider unfollows an organizer
// Synchronous: sets follow inactive, removes invited enrollments
// on organizer open PUBLIC rides only. Accepted/maybe/declined
// and all private ride enrollments are untouched.
// Body: { follower_id, following_id }
// ============================================================
if ($method === 'DELETE' && $parts[0] === 'follows' && count($parts) === 1) {
    $body        = getBody();
    $followerId  = $body['follower_id']  ?? null;
    $followingId = $body['following_id'] ?? null;
    if (!$followerId || !$followingId) error('Missing follower_id or following_id');

    $db = getDb();

    // 1. Set follow inactive
    $deactivate = $db->prepare('
        UPDATE follows SET status = ?
        WHERE follower_id = ? AND following_id = ?
    ');
    $deactivate->execute(['inactive', $followerId, $followingId]);

    // 2. Get organizer open public rides
    $rides = $db->prepare('
        SELECT ride_id FROM rides
        WHERE organizer_id = ?
          AND ride_status = ?
          AND is_public = 1
    ');
    $rides->execute([$followingId, 'open']);
    $openRideIds = array_column($rides->fetchAll(), 'ride_id');

    $removed = 0;
    foreach ($openRideIds as $rideId) {
        // Remove ONLY invited enrollments � never touch accepted/maybe/declined
        $del = $db->prepare('
            DELETE FROM enrollments
            WHERE ride_id = ?
              AND user_id = ?
              AND status = ?
        ');
        $del->execute([$rideId, $followerId, 'invited']);
        $removed += $del->rowCount();
    }

    success([
        'message'              => 'Unfollowed',
        'enrollments_removed'  => $removed,
        'rides_checked'        => count($openRideIds)
    ]);
}

// No route matched
error('Not found', 404);
