import { invoke } from './bridge';

// Matches the endpoint and payload convention of src/services/request.js.
const base = 'https://api.vrchat.cloud/api/1/';

export async function request(endpoint, { method = 'GET', params, imageData, tag } = {}) {
    const url = new URL(endpoint, base);
    if (url.origin !== new URL(base).origin || !url.pathname.startsWith('/api/1/')) {
        throw new Error('Invalid VRChat API endpoint');
    }
    if (method === 'GET' && params) {
        for (const [key, value] of Object.entries(params)) url.searchParams.set(key, value);
    }
    const result = await invoke('request', {
        url: url.href,
        method,
        body: method !== 'GET' && params ? JSON.stringify(params) : undefined,
        imageData,
        tag
    });
    let data = null;
    if (result.body) {
        try {
            data = JSON.parse(result.body);
        } catch {
            data = result.body;
        }
    }
    if (result.status < 200 || result.status >= 300 || data?.error) {
        const error = new Error(data?.error?.message || `HTTP ${result.status}`);
        error.status = result.status;
        error.data = data;
        throw error;
    }
    return data;
}

export async function login(username, password) {
    // Password is sent once to native memory; neither preferences nor web storage receives it.
    return invoke('login', { username, password });
}

export const session = () => request('auth/user');
export const verify = (method, code) =>
    request(`auth/twofactorauth/${method.toLowerCase()}/verify`, { method: 'POST', params: { code } });
export const logout = () => invoke('logout');
export const friends = () => request('auth/user/friends', { params: { n: 100, offset: 0 } });
export const worlds = (search) => request('worlds', { params: { search, n: 30 } });
export const myWorlds = (id) => request('worlds', { params: { userId: id, n: 100 } });
export const avatars = (id) => request('avatars', { params: { userId: id, n: 100 } });
export const groups = (id) => request(`users/${encodeURIComponent(id)}/groups`, { params: { n: 100 } });
export const favorites = () => request('favorites', { params: { n: 100 } });
export const notifications = () => request('notifications', { params: { n: 100 } });
export const getWorld = (id) => request(`worlds/${encodeURIComponent(id)}`);
export const getAvatar = (id) => request(`avatars/${encodeURIComponent(id)}`);
export const saveWorld = (id, params) => request(`worlds/${encodeURIComponent(id)}`, { method: 'PUT', params });
export const saveAvatar = (id, params) => request(`avatars/${encodeURIComponent(id)}`, { method: 'PUT', params });

export async function uploadImage(kind, entity, imageData) {
    // This is the direct File API route used by VRCX's worldRequest/avatarRequest.
    // A failed upload never mutates the entity.
    const tag = kind === 'world' ? 'worldimage' : 'avatarimage';
    const uploaded = await request('file/image', { method: 'POST', tag, imageData });
    // VRCX's Linux path uses /file/image and reads the URL from the newest file version.
    const latestVersion = uploaded.versions?.[uploaded.versions.length - 1];
    const imageUrl = latestVersion?.file?.url || uploaded.fileUrl || uploaded.url;
    if (!imageUrl || !imageUrl.startsWith('https://')) throw new Error('File API returned no image URL');
    const updated =
        kind === 'world'
            ? await saveWorld(entity.id, { id: entity.id, imageUrl })
            : await saveAvatar(entity.id, { id: entity.id, imageUrl });
    if (updated.imageUrl !== imageUrl) throw new Error('Image URL was not confirmed by VRChat');
    return updated;
}
