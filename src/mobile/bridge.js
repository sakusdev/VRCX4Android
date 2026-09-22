// The only renderer/native boundary. The native side owns cookies and never returns them.
let nextId = 0;
const pending = new Map();

window.vrcxAndroidResponse = (id, result) => {
    const entry = pending.get(id);
    if (!entry) return;
    pending.delete(id);
    if (result.error) entry.reject(new Error(result.error));
    else entry.resolve(result.value);
};

export function invoke(action, payload = {}) {
    if (!window.VrcxAndroid) return Promise.reject(new Error('Android bridge unavailable'));
    return new Promise((resolve, reject) => {
        const id = ++nextId;
        pending.set(id, { resolve, reject });
        try {
            window.VrcxAndroid.send(JSON.stringify({ id, action, ...payload }));
        } catch (error) {
            pending.delete(id);
            reject(error);
        }
    });
}
