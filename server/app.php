<?php
declare(strict_types=1);

// CORS
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: GET, POST, OPTIONS');
header('Access-Control-Allow-Headers: Content-Type');

if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(204);
    exit;
}

header('Content-Type: application/json; charset=utf-8');

$dbConfig = [
    'host' => getenv('MYSQL_HOST') ?: 'localhost',
    'port' => getenv('MYSQL_PORT') ?: '3306',
    'name' => getenv('MYSQL_DATABASE') ?: 'admlova_bebidasonline',
    'user' => getenv('MYSQL_USER') ?: 'admlova_consultaDB',
    'pass' => getenv('MYSQL_PASSWORD') ?: 'consultaDB',
];

function getPdo(array $cfg): PDO {
    $dsn = sprintf(
        'mysql:host=%s;port=%s;dbname=%s;charset=utf8mb4',
        $cfg['host'],
        $cfg['port'],
        $cfg['name']
    );

    return new PDO($dsn, $cfg['user'], $cfg['pass'], [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
        PDO::ATTR_EMULATE_PREPARES => false,
    ]);
}

function initDb(PDO $pdo): void {
    $pdo->exec(
        'CREATE TABLE IF NOT EXISTS expenses (
            id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
            item VARCHAR(255) NOT NULL,
            place VARCHAR(255) NOT NULL,
            amount DECIMAL(12,2) NULL,
            raw_text TEXT NULL,
            timestamp BIGINT NOT NULL,
            `USUARIO` VARCHAR(255) NULL,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci'
    );
}

// Nueva: asegurar existencia de columna (migración si la tabla ya existía)
function ensureColumnExists(PDO $pdo, string $table, string $column, string $definition): void {
    try {
        $dbName = $GLOBALS['dbConfig']['name'] ?? '';
        if ($dbName === '') return;
        $stmt = $pdo->prepare('SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = :db AND TABLE_NAME = :table AND COLUMN_NAME = :column');
        $stmt->execute([':db' => $dbName, ':table' => $table, ':column' => $column]);
        $count = (int)$stmt->fetchColumn();
        if ($count === 0) {
            // Añadir columna si no existe
            $pdo->exec("ALTER TABLE `$table` ADD COLUMN `$column` $definition");
        }
    } catch (Throwable $e) {
        // No detener ejecución si la migración falla por permisos/DR; silenciar
    }
}

function jsonInput(): array {
    $raw = file_get_contents('php://input') ?: '';
    $data = json_decode($raw, true);
    return is_array($data) ? $data : [];
}

function sendJson($payload, int $status = 200): void {
    http_response_code($status);
    echo json_encode($payload, JSON_UNESCAPED_UNICODE);
    exit;
}

// Cargar variables desde .env (opcional)
$dotEnv = __DIR__ . '/.env';
if (file_exists($dotEnv)) {
    foreach (file($dotEnv, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES) as $line) {
        $line = trim($line);
        if ($line === '' || str_starts_with($line, '#') || strpos($line, '=') === false) continue;
        [$k, $v] = explode('=', $line, 2);
        $k = trim($k); $v = trim($v);
        putenv("$k=$v");
        $_ENV[$k] = $v;
        $_SERVER[$k] = $v;
    }
}

// Ruta solicitada (más robusta si usas rewrite a app.php)
$rawUri = parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH) ?? '/';
$scriptName = $_SERVER['SCRIPT_NAME'] ?? '';
$scriptDir = rtrim(dirname($scriptName), '/\\');

if ($scriptDir !== '' && $scriptDir !== '/' && strpos($rawUri, $scriptDir) === 0) {
    $rawUri = substr($rawUri, strlen($scriptDir));
}
$uri = '/' . ltrim($rawUri, '/');
$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';
$isExpensesRoute = ($uri === '/expenses' || $uri === '/app.php/expenses' || $uri === 'expenses');

$pdo = getPdo($dbConfig);
initDb($pdo);

// Asegurar columna USUARIO para instalaciones previas
ensureColumnExists($pdo, 'expenses', 'USUARIO', "VARCHAR(255) NULL DEFAULT ''");

try {
    if ($isExpensesRoute && $method === 'POST') {
        $data = jsonInput();

        $item = trim((string)($data['item'] ?? ''));
        $place = trim((string)($data['place'] ?? ''));
        $amountRaw = $data['amount'] ?? null;
        $amount = ($amountRaw === '' || $amountRaw === null) ? null : (float)$amountRaw;
        $rawText = (string)($data['rawText'] ?? '');
        $timestamp = (int)($data['timestamp'] ?? 0);

        // Aceptar varias keys para el usuario: 'user', 'usuario' o 'USUARIO'
        $usuario = '';
        if (isset($data['USUARIO'])) $usuario = trim((string)$data['USUARIO']);
        elseif (isset($data['usuario'])) $usuario = trim((string)$data['usuario']);
        elseif (isset($data['user'])) $usuario = trim((string)$data['user']);

        if ($item === '' || $place === '' || $usuario === '' || $timestamp === 0) {
            sendJson(['error' => 'item, place, USUARIO y timestamp son obligatorios'], 400);
        }

        $stmt = $pdo->prepare(
            'INSERT INTO expenses (item, place, amount, raw_text, timestamp, `USUARIO`)
             VALUES (:item, :place, :amount, :raw_text, :timestamp, :usuario)'
        );
        $stmt->execute([
            ':item' => $item,
            ':place' => $place,
            ':amount' => $amount,
            ':raw_text' => $rawText,
            ':timestamp' => $timestamp,
            ':usuario' => $usuario,
        ]);

        sendJson(['id' => (int)$pdo->lastInsertId(), 'status' => 'ok'], 201);
    }

    if ($isExpensesRoute && $method === 'GET') {
        // Obtener usuario desde query param o headers (acepta varias keys)
        $usuario = '';
        if (!empty($_GET['usuario'])) {
            $usuario = trim((string)$_GET['usuario']);
        } else {
            // comprobar encabezados comunes (X-USUARIO, Usuario, user)
            $hdrs = [];
            foreach ($_SERVER as $k => $v) {
                if (stripos($k, 'HTTP_') === 0) {
                    $hdrs[str_replace('HTTP_', '', $k)] = $v;
                }
            }
            // revisar variantes
            $candidates = ['X_USUARIO','X-USUARIO','USUARIO','Usuario','usuario','USER','user','USERNAME','username'];
            foreach ($candidates as $c) {
                // buscar normalizado en $_SERVER
                $key1 = 'HTTP_' . strtoupper(str_replace('-', '_', $c));
                if (!empty($_SERVER[$key1])) {
                    $usuario = trim((string)$_SERVER[$key1]);
                    break;
                }
                // buscar en headers extraídos
                $k2 = strtoupper(str_replace(['-',' '], '_', $c));
                if (!empty($hdrs[$k2])) {
                    $usuario = trim((string)$hdrs[$k2]);
                    break;
                }
            }
        }

        if ($usuario === '') {
            sendJson(['error' => 'El parámetro "usuario" es obligatorio (query ?usuario=... o header X-USUARIO)'], 400);
        }

        // Filtrar por USUARIO en la consulta
        $stmt = $pdo->prepare(
            'SELECT id, item, place, amount, raw_text, timestamp, `USUARIO`
             FROM expenses
             WHERE `USUARIO` = :usuario
             ORDER BY id DESC'
        );
        $stmt->execute([':usuario' => $usuario]);
        sendJson($stmt->fetchAll());
    }

    sendJson(['error' => 'Ruta no encontrada'], 404);
} catch (Throwable $e) {
    sendJson(['error' => 'Error interno', 'detail' => $e->getMessage()], 500);
}
