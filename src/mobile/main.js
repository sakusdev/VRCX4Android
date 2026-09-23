import './android.css';
import { invoke } from './bridge';

// Keep the upstream VRCX renderer intact. Android replaces only the platform
// services normally supplied by Electron/.NET.
globalThis.WINDOWS = false;
globalThis.LINUX = true;
globalThis.ANDROID = true;
window.isVrOverlay = false;
document.documentElement.classList.add('vrcx-android');

window.interopApi = {
    callDotNetMethod(className, methodName, args = []) {
        return invoke('interop', { className, methodName, args });
    }
};

const noopSubscription = () => () => {};
window.electron = {
    getArch: async () => 'arm64',
    getClipboardText: async () => navigator.clipboard?.readText?.().catch(() => '') ?? '',
    getNoUpdater: async () => true,
    setTrayIconNotification: async () => {},
    openFileDialog: async () => null,
    openDirectoryDialog: async () => null,
    onWindowPositionChanged: noopSubscription,
    onWindowSizeChanged: noopSubscription,
    onWindowStateChange: noopSubscription,
    onBrowserFocus: noopSubscription,
    desktopNotification: async (title, body) => {
        console.info('[Android notification]', title, body);
    },
    restartApp: async () => location.reload(),
    getOverlayWindow: async () => false,
    updateVr: async () => false,
    ipcRenderer: {
        on: noopSubscription
    }
};

window.vrcxAndroidBack = () => {
    if (history.length > 1) history.back();
    else invoke('background').catch(() => {});
};

// This is the real desktop renderer entry point, not a mobile reimplementation.
await import('../app.js');
