import { setActivePinia } from 'pinia';

import { pinia } from '../stores';
import { invoke } from './bridge';

Error.stackTraceLimit = 30;

// Android replaces only the platform services normally supplied by Electron/.NET.
// This module MUST be evaluated before the upstream src/app.js entry point.
//
// VRCX has a large circular module graph (plugins -> router -> views -> stores).
// Electron's desktop entry happens to activate Pinia before component setup,
// but the Android bootstrap must make that ordering explicit.
setActivePinia(pinia);

globalThis.WINDOWS = false;
globalThis.LINUX = true;
globalThis.ANDROID = true;
window.isVrOverlay = false;
document.documentElement.classList.add('vrcx-android');

// Android WebView does not always expose the browser speech synthesis API.
// The desktop notification store still asks for an empty voice list on startup.
if (!window.speechSynthesis) {
    window.speechSynthesis = {
        getVoices: () => [],
        cancel: () => {},
        speak: () => {}
    };
}

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

window.addEventListener('error', (event) => {
    console.error('VRCX_ANDROID_RENDERER_FAILED', event.error?.stack || event.error || event.message);
});
window.addEventListener('unhandledrejection', (event) => {
    console.error('VRCX_ANDROID_RENDERER_FAILED', event.reason?.stack || event.reason);
});
