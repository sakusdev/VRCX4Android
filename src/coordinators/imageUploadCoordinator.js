import { toast } from 'vue-sonner';

import { $throw, request } from '../services/request';
import { AppDebug } from '../services/appConfig.js';
import { extractFileId } from '../shared/utils';
import { imageRequest } from '../api';

function resolveMessage(message) {
    if (typeof message === 'function') {
        return message();
    }
    return message;
}

function getInputElement(selector) {
    if (!selector) {
        return null;
    }
    if (typeof selector === 'function') {
        return selector();
    }
    if (typeof selector === 'string') {
        return document.querySelector(selector);
    }
    return selector;
}

export function handleImageUploadInput(event, options = {}) {
    const {
        inputSelector,
        // 20MB
        maxSize = 20000000,
        acceptPattern = /image.*/,
        tooLargeMessage,
        invalidTypeMessage,
        onClear
    } = options;

    const clearInput = () => {
        onClear?.();
        const input = getInputElement(inputSelector);
        if (input) {
            input.value = '';
        }
    };

    const files = event?.target?.files || event?.dataTransfer?.files;
    if (!files || files.length === 0) {
        clearInput();
        return { file: null, clearInput };
    }

    const file = files[0];
    if (file.size >= maxSize) {
        if (tooLargeMessage) {
            toast.error(resolveMessage(tooLargeMessage));
        }
        clearInput();
        return { file: null, clearInput };
    }

    let acceptRegex = null;
    if (acceptPattern) {
        acceptRegex = acceptPattern instanceof RegExp ? acceptPattern : new RegExp(acceptPattern);
    }

    if (acceptRegex && !acceptRegex.test(file.type)) {
        if (invalidTypeMessage) {
            toast.error(resolveMessage(invalidTypeMessage));
        }
        clearInput();
        return { file: null, clearInput };
    }

    return { file, clearInput };
}

/**
 * @param {string} base64Data - Base64 encoded image
 * @returns {Promise<string>} Resized base64 encoded image
 */
export async function resizeImageToFitLimits(base64Data) {
    if (globalThis.ANDROID !== true) {
        return AppApi.ResizeImageToFitLimits(base64Data);
    }

    const maxWidth = 2000;
    const maxHeight = 2000;
    const maxSize = 10_000_000;

    const imageBytes = Uint8Array.from(atob(base64Data), (char) => char.charCodeAt(0));
    const imageUrl = URL.createObjectURL(new Blob([imageBytes]));
    const image = await new Promise((resolve, reject) => {
        const img = new Image();
        img.onload = () => {
            URL.revokeObjectURL(imageUrl);
            resolve(img);
        };
        img.onerror = (error) => {
            URL.revokeObjectURL(imageUrl);
            reject(error);
        };
        img.src = imageUrl;
    });

    let width = image.width;
    let height = image.height;

    if (width > maxWidth) {
        const factor = width / maxWidth;
        width = maxWidth;
        height = Math.round(height / factor);
    }
    if (height > maxHeight) {
        const factor = height / maxHeight;
        height = maxHeight;
        width = Math.round(width / factor);
    }

    const canvas = document.createElement('canvas');
    const ctx = canvas.getContext('2d');

    const render = (w, h) => {
        canvas.width = Math.max(1, w);
        canvas.height = Math.max(1, h);
        ctx.clearRect(0, 0, canvas.width, canvas.height);
        ctx.drawImage(image, 0, 0, canvas.width, canvas.height);
        return canvas.toDataURL('image/png').split(',', 2)[1];
    };

    let resized = render(width, height);
    const decodedSize = (value) => Math.floor((value.length * 3) / 4);

    for (let i = 0; i < 250 && decodedSize(resized) > maxSize; i++) {
        if (width > height) {
            const nextWidth = Math.max(1, width - 25);
            height = Math.max(1, Math.round(height / (width / nextWidth)));
            width = nextWidth;
        } else {
            const nextHeight = Math.max(1, height - 25);
            width = Math.max(1, Math.round(width / (height / nextHeight)));
            height = nextHeight;
        }
        resized = render(width, height);
    }

    if (decodedSize(resized) > maxSize) {
        throw new Error('Failed to get image into target filesize.');
    }

    return resized;
}

/**
 * Upload image through AWS
 *
 * @param {'avatar' | 'world'} type
 * @param {object} opts
 * @param {string} opts.entityId - Avatar or world id
 * @param {string} opts.imageUrl - Current imageUrl on the entity
 * @param {string} opts.base64File - Base64 encoded image data
 * @param {Blob} opts.blob - The original blob (used for file size)
 */
export async function uploadImageLegacy(type, { entityId, imageUrl, base64File, blob }) {
    const apiMap = {
        avatar: {
            uploadImage: imageRequest.uploadAvatarImage,
            fileStart: imageRequest.uploadAvatarImageFileStart,
            fileFinish: imageRequest.uploadAvatarImageFileFinish,
            sigStart: imageRequest.uploadAvatarImageSigStart,
            sigFinish: imageRequest.uploadAvatarImageSigFinish,
            setImage: imageRequest.setAvatarImage
        },
        world: {
            uploadImage: imageRequest.uploadWorldImage,
            fileStart: imageRequest.uploadWorldImageFileStart,
            fileFinish: imageRequest.uploadWorldImageFileFinish,
            sigStart: imageRequest.uploadWorldImageSigStart,
            sigFinish: imageRequest.uploadWorldImageSigFinish,
            setImage: imageRequest.setWorldImage
        }
    };
    const api = apiMap[type];

    // Android deliberately reuses the upstream /file/image path used by VRCX's
    // Linux image APIs. It avoids pulling the desktop librsync signer into the
    // Android shell while keeping the existing desktop crop/dialog UI intact.
    if (globalThis.ANDROID === true) {
        const tag = type === 'avatar' ? 'avatarimage' : 'worldimage';
        const uploaded = await request('file/image', {
            uploadImage: true,
            matchingDimensions: false,
            postData: JSON.stringify({ tag }),
            imageData: base64File
        });
        const latestVersion = uploaded.versions?.[uploaded.versions.length - 1];
        const newImageUrl = latestVersion?.file?.url;
        if (!newImageUrl) {
            $throw(0, `${type} image upload returned no file URL`, 'file/image');
        }
        const setRes = await api.setImage({ id: entityId, imageUrl: newImageUrl });
        if (setRes.json.imageUrl !== newImageUrl) {
            $throw(0, `${type} image change failed`, newImageUrl);
        }
        return;
    }

    const fileMd5 = await AppApi.MD5File(base64File);
    const fileSizeInBytes = parseInt(blob.size, 10);
    const base64SignatureFile = await AppApi.SignFile(base64File);
    const signatureMd5 = await AppApi.MD5File(base64SignatureFile);
    const signatureSizeInBytes = parseInt(await AppApi.FileLength(base64SignatureFile), 10);
    const fileId = extractFileId(imageUrl);

    // imageInit
    const uploadRes = await api.uploadImage({ fileMd5, fileSizeInBytes, signatureMd5, signatureSizeInBytes }, fileId);
    const uploadedFileId = uploadRes.json.id;
    const fileVersion = uploadRes.json.versions[uploadRes.json.versions.length - 1].version;

    // imageFileStart
    const fileStartRes = await api.fileStart({
        fileId: uploadedFileId,
        fileVersion
    });

    // uploadImageFileAWS
    const fileAwsRes = await webApiService.execute({
        url: fileStartRes.json.url,
        uploadFilePUT: true,
        fileData: base64File,
        fileMIME: 'image/png',
        fileMD5: fileMd5
    });
    if (fileAwsRes.status !== 200) {
        $throw(fileAwsRes.status, `${type} image upload failed`, fileStartRes.json.url);
    }

    // imageFileFinish
    await api.fileFinish({ fileId: uploadedFileId, fileVersion });

    // imageSigStart
    const sigStartRes = await api.sigStart({
        fileId: uploadedFileId,
        fileVersion
    });

    // uploadImageSigAWS
    const sigAwsRes = await webApiService.execute({
        url: sigStartRes.json.url,
        uploadFilePUT: true,
        fileData: base64SignatureFile,
        fileMIME: 'application/x-rsync-signature',
        fileMD5: signatureMd5
    });
    if (sigAwsRes.status !== 200) {
        $throw(sigAwsRes.status, `${type} image upload failed`, sigStartRes.json.url);
    }

    // imageSigFinish
    await api.sigFinish({ fileId: uploadedFileId, fileVersion });

    // imageSet
    const newImageUrl = `${AppDebug.endpointDomain}/file/${uploadedFileId}/${fileVersion}/file`;
    const setRes = await api.setImage({ id: entityId, imageUrl: newImageUrl });
    if (setRes.json.imageUrl !== newImageUrl) {
        $throw(0, `${type} image change failed`, newImageUrl);
    }
}
