import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
function normalizeBasePath(basePath) {
    var trimmed = basePath === null || basePath === void 0 ? void 0 : basePath.trim();
    if (!trimmed || trimmed === '/') {
        return '/';
    }
    return "/".concat(trimmed.replace(/^\/+|\/+$/g, ''), "/");
}
export default defineConfig(function (_a) {
    var mode = _a.mode;
    var env = loadEnv(mode, '.', '');
    var basePath = normalizeBasePath(env.VITE_APP_BASE_PATH);
    return {
        base: basePath,
        plugins: [react()],
        server: {
            host: 'localhost',
            port: 5173,
            strictPort: true,
        },
        preview: {
            host: '0.0.0.0',
            port: 4173,
            strictPort: true,
        },
    };
});
