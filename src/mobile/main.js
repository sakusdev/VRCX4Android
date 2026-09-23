import './android.css';
import './platform.js';

// Keep the actual upstream VRCX renderer as a static dependency.
// This intentionally matches the desktop entry-point evaluation order;
// using a dynamic import here can break Pinia's circular module graph.
import '../app.js';

document.documentElement.dataset.vrcxMounted = 'true';
console.info('VRCX_ANDROID_RENDERER_MOUNTED');
