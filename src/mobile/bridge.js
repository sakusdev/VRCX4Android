// Android renderer/native boundary.
let nextId = 0;
const pending = new Map();

window.vrcxAndroidResponse = (id, result) => {
    const entry = pending.get(id);
    if (!entry) return;
    pending.delete(id);
    if (result.error) entry.reject(new Error(result.error));
    else entry.resolve(result.value);
};

function toJsonValue(value) {
    if (value instanceof Map) {
        return Object.fromEntries(Array.from(value.entries(), ([key, item]) => [key, toJsonValue(item)]));
    }
    if (Array.isArray(value)) {
        return value.map(toJsonValue);
    }
    if (value && typeof value === 'object') {
        return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, toJsonValue(item)]));
    }
    return value;
}

export function invoke(action, payload = {}) {
    if (!window.VrcxAndroid) return Promise.reject(new Error('Android bridge unavailable'));
    return new Promise((resolve, reject) => {
        const id = ++nextId;
        pending.set(id, { resolve, reject });
        try {
            window.VrcxAndroid.send(JSON.stringify(toJsonValue({ id, action, ...payload })));
        } catch (error) {
            pending.delete(id);
            reject(error);
        }
    });
}
