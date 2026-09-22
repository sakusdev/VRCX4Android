import { beforeEach, describe, expect, it, vi } from 'vitest';

const { invoke } = vi.hoisted(() => ({
    invoke: vi.fn()
}));

vi.mock('./bridge', () => ({
    invoke
}));

const api = await import('./api');

describe('Android API adapter', () => {
    beforeEach(() => {
        invoke.mockReset();
    });

    it('encodes GET parameters into the VRChat API URL', async () => {
        invoke.mockResolvedValue({
            status: 200,
            body: '[]'
        });

        await api.worlds('test world');

        expect(invoke).toHaveBeenCalledOnce();
        const [action, payload] = invoke.mock.calls[0];
        expect(action).toBe('request');
        expect(payload.method).toBe('GET');
        const url = new URL(payload.url);
        expect(url.origin).toBe('https://api.vrchat.cloud');
        expect(url.pathname).toBe('/api/1/worlds');
        expect(url.searchParams.get('search')).toBe('test world');
        expect(url.searchParams.get('n')).toBe('30');
    });

    it('matches upstream parameters when listing the current user's worlds', async () => {
        invoke.mockResolvedValue({
            status: 200,
            body: '[]'
        });

        await api.myWorlds();

        const url = new URL(invoke.mock.calls[0][1].url);
        expect(url.pathname).toBe('/api/1/worlds');
        expect(Object.fromEntries(url.searchParams)).toMatchObject({
            n: '50',
            offset: '0',
            sort: 'updated',
            order: 'descending',
            releaseStatus: 'all',
            user: 'me'
        });
        expect(url.searchParams.has('userId')).toBe(false);
    });

    it('uses the newest /file/image version URL before updating a world', async () => {
        const imageUrl = 'https://api.vrchat.cloud/api/1/file/file_test/3/file';
        invoke
            .mockResolvedValueOnce({
                status: 200,
                body: JSON.stringify({
                    id: 'file_test',
                    versions: [
                        { version: 2, file: { url: 'https://api.vrchat.cloud/api/1/file/file_test/2/file' } },
                        { version: 3, file: { url: imageUrl } }
                    ]
                })
            })
            .mockResolvedValueOnce({
                status: 200,
                body: JSON.stringify({
                    id: 'wrld_test',
                    imageUrl
                })
            });

        const updated = await api.uploadImage('world', { id: 'wrld_test' }, 'aGVsbG8=');

        expect(updated.imageUrl).toBe(imageUrl);
        expect(invoke).toHaveBeenCalledTimes(2);
        expect(invoke.mock.calls[0][1]).toMatchObject({
            method: 'POST',
            tag: 'worldimage',
            imageData: 'aGVsbG8='
        });
        expect(invoke.mock.calls[1][1]).toMatchObject({
            method: 'PUT',
            body: JSON.stringify({ id: 'wrld_test', imageUrl })
        });
    });
});
