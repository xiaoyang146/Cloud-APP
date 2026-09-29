<?php
/**
 * 更新日志接口
 *  GET /APPyingyon/gengxin/get_update_log.php?app_name=xxx&package_name=xxx&get_all=1
 *
 * 返回（新格式，App 1.0.7+ 优先使用 versions 结构化数据）：
 * {
 *   "success": true,
 *   "total_versions": 3,
 *   "update_log": "v1.0.8:\n...\n\nv1.0.7:\n...",
 *   "versions": [
 *     {"version_name":"1.0.8","version_code":3,"update_log":"...","created_at":"2026-05-31 19:05:50"},
 *     ...
 *   ]
 * }
 *
 * 兼容说明：老版本 App 只读 update_log 纯文本字段，新字段不影响其工作。
 */
header('Content-Type: application/json; charset=utf-8');
ini_set('display_errors', 0);
error_reporting(0);

try {
    require_once __DIR__ . '/../db.php';

    if (!isset($conn) || $conn->connect_error) {
        throw new Exception('数据库连接失败');
    }

    // 支持参数：?app_name=xxx&package_name=xxx&get_all=1
    $app_name     = $_GET['app_name']     ?? '';
    $package_name = $_GET['package_name'] ?? '';

    $fields = "version_name, version_code, update_log, created_at";
    $rows   = [];

    // ① 优先按包名匹配（最准确，避免应用名不完全一致时查不到）
    if (!empty($package_name)) {
        $stmt = $conn->prepare("SELECT $fields FROM versions WHERE package_name = ? ORDER BY version_code DESC");
        if ($stmt) {
            $stmt->bind_param("s", $package_name);
            $stmt->execute();
            $result = $stmt->get_result();
            while ($row = $result->fetch_assoc()) {
                $rows[] = $row;
            }
            $stmt->close();
        }
    }

    // ② 回退：按应用名称匹配
    if (count($rows) === 0 && !empty($app_name)) {
        $stmt = $conn->prepare("SELECT $fields FROM versions WHERE app_name = ? ORDER BY version_code DESC");
        if ($stmt) {
            $stmt->bind_param("s", $app_name);
            $stmt->execute();
            $result = $stmt->get_result();
            while ($row = $result->fetch_assoc()) {
                $rows[] = $row;
            }
            $stmt->close();
        }
    }

    // ③ 最后回退：不加过滤条件，返回全部版本记录
    if (count($rows) === 0) {
        $stmt = $conn->prepare("SELECT $fields FROM versions ORDER BY version_code DESC");
        if ($stmt) {
            $stmt->execute();
            $result = $stmt->get_result();
            while ($row = $result->fetch_assoc()) {
                $rows[] = $row;
            }
            $stmt->close();
        }
    }

    $versions = [];
    $logs     = [];
    foreach ($rows as $row) {
        $versionName = (string)($row['version_name'] ?? '');
        $updateLog   = trim((string)($row['update_log'] ?? ''));
        if ($updateLog === '' || strtolower($updateLog) === 'null') {
            $updateLog = '暂无更新说明';
        }
        $versions[] = [
            'version_name' => $versionName,
            'version_code' => (int)($row['version_code'] ?? 0),
            'update_log'   => $updateLog,
            'created_at'   => (string)($row['created_at'] ?? '')
        ];
        $logs[] = "v{$versionName}:\n{$updateLog}";
    }

    if (count($versions) > 0) {
        echo json_encode([
            'success'        => true,
            'total_versions' => count($versions),
            'update_log'     => implode("\n\n", $logs),
            'versions'       => $versions
        ], JSON_UNESCAPED_UNICODE);
    } else {
        echo json_encode([
            'success'        => false,
            'total_versions' => 0,
            'update_log'     => '暂无更新日志',
            'versions'       => []
        ], JSON_UNESCAPED_UNICODE);
    }

} catch (Exception $e) {
    echo json_encode([
        'success'        => false,
        'total_versions' => 0,
        'update_log'     => '暂无更新日志',
        'versions'       => [],
        'message'        => $e->getMessage()
    ], JSON_UNESCAPED_UNICODE);
}

if (isset($conn)) $conn->close();
?>