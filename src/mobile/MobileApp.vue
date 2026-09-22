<template>
    <div class="mobile-shell">
        <header>
            <button v-if="selected" class="icon" @click="selected = null">‹</button>
            <strong>VRCX <small>Android · unofficial</small></strong>
            <button v-if="user" class="right" @click="signOut">Sign out</button>
        </header>

        <main>
            <p v-if="error" role="alert" class="error">{{ error }}</p>
            <p v-if="busy" role="status">Loading…</p>

            <section v-if="!user" class="login">
                <h1>VRChat login</h1>
                <form v-if="!twoFactor.length" @submit.prevent="signIn">
                    <label>Username or email <input v-model="username" autocomplete="username" required /></label>
                    <label
                        >Password <input v-model="password" type="password" autocomplete="current-password" required
                    /></label>
                    <button :disabled="busy" type="submit">Sign in</button>
                </form>
                <form v-else @submit.prevent="submitCode">
                    <label
                        >Verification method
                        <select v-model="factor">
                            <option v-for="item in twoFactor" :key="item" :value="item">{{ item }}</option>
                        </select>
                    </label>
                    <label
                        >One-time code
                        <input v-model.trim="code" inputmode="numeric" autocomplete="one-time-code" required
                    /></label>
                    <button :disabled="busy" type="submit">Verify</button>
                    <button type="button" class="secondary" @click="cancelCode">Cancel</button>
                </form>
                <p class="muted">
                    Your password is used for this sign-in only. The Android Keystore protects the session cookies.
                </p>
            </section>

            <template v-else>
                <section v-if="selected" class="details">
                    <img v-if="selected.imageUrl" :src="selected.imageUrl" alt="" />
                    <h1>{{ selected.name }}</h1>
                    <p>{{ selected.description }}</p>
                    <p class="muted">{{ selected.id }}</p>
                    <template v-if="selected.authorId === user.id">
                        <label>Name <input v-model="editName" /></label>
                        <label>Description <textarea v-model="editDescription" rows="4"></textarea></label>
                        <button :disabled="busy" @click="saveDetails">Save details</button>
                        <label class="picker"
                            >Change image <input type="file" accept="image/*" @change="chooseImage"
                        /></label>
                        <div v-if="preview" class="preview">
                            <img :src="preview" alt="Cropped image preview" />
                            <p class="muted">4:3 crop, resized to 1200 × 900.</p>
                            <button :disabled="busy" @click="saveImage">Upload and change image</button>
                        </div>
                    </template>
                </section>
                <section v-else>
                    <div class="profile">
                        <img
                            v-if="user.userIcon || user.currentAvatarImageUrl"
                            :src="user.userIcon || user.currentAvatarImageUrl"
                            alt="" />
                        <div>
                            <strong>{{ user.displayName }}</strong
                            ><small>{{ user.username }}</small>
                        </div>
                    </div>
                    <nav aria-label="VRCX sections">
                        <button
                            v-for="tab in tabs"
                            :key="tab"
                            :class="{ active: currentTab === tab }"
                            @click="switchTab(tab)">
                            {{ tab }}
                        </button>
                    </nav>
                    <form v-if="currentTab === 'Worlds'" class="search" @submit.prevent="loadTab">
                        <input v-model="search" placeholder="Search worlds" aria-label="Search worlds" />
                        <button type="submit">Search</button>
                    </form>
                    <div v-if="!items.length && !busy" class="empty">No items found.</div>
                    <button v-for="item in items" :key="item.id || item.objectId" class="item" @click="openItem(item)">
                        <img
                            v-if="item.thumbnailImageUrl || item.imageUrl || item.userIcon"
                            :src="item.thumbnailImageUrl || item.imageUrl || item.userIcon"
                            alt="" />
                        <span
                            ><strong>{{ item.name || item.displayName || item.objectName || item.id }}</strong
                            ><small>{{ item.description || item.statusDescription || item.type }}</small></span
                        >
                        <span aria-hidden="true">›</span>
                    </button>
                </section>
            </template>
        </main>
    </div>
</template>

<script setup>
    import { onMounted, onUnmounted, ref } from 'vue';
    import * as api from './api';
    import { invoke } from './bridge';

    const tabs = ['Friends', 'Worlds', 'My Worlds', 'Avatars', 'Groups', 'Favorites', 'Notifications'];
    const user = ref(null);
    const items = ref([]);
    const selected = ref(null);
    const currentTab = ref('Friends');
    const username = ref('');
    const password = ref('');
    const twoFactor = ref([]);
    const factor = ref('totp');
    const code = ref('');
    const search = ref('');
    const busy = ref(false);
    const error = ref('');
    const editName = ref('');
    const editDescription = ref('');
    const preview = ref('');
    const imageData = ref('');

    async function run(fn) {
        busy.value = true;
        error.value = '';
        try {
            return await fn();
        } catch (e) {
            error.value = e.message;
            return null;
        } finally {
            busy.value = false;
        }
    }

    async function restore() {
        busy.value = true;
        try {
            const result = await api.session();
            if (result.requiresTwoFactorAuth?.length) {
                twoFactor.value = result.requiresTwoFactorAuth;
                factor.value = twoFactor.value[0];
            } else {
                user.value = result;
                await loadItems();
            }
        } catch (e) {
            if (e.status !== 401) error.value = e.message;
        } finally {
            busy.value = false;
        }
    }

    async function signIn() {
        await run(async () => {
            const result = await api.login(username.value, password.value);
            password.value = '';
            if (result.requiresTwoFactorAuth?.length) {
                twoFactor.value = result.requiresTwoFactorAuth;
                factor.value = twoFactor.value[0];
            } else {
                user.value = result;
                await loadItems();
            }
        });
        password.value = '';
    }

    async function submitCode() {
        await run(async () => {
            await api.verify(factor.value, code.value);
            code.value = '';
            twoFactor.value = [];
            user.value = await api.session();
            await loadItems();
        });
    }

    async function cancelCode() {
        await api.logout();
        twoFactor.value = [];
        code.value = '';
    }

    async function signOut() {
        await run(async () => {
            await api.logout();
            user.value = null;
            items.value = [];
            selected.value = null;
        });
    }

    async function switchTab(tab) {
        currentTab.value = tab;
        items.value = [];
        await loadTab();
    }

    async function loadItems() {
        const id = user.value?.id;
        const load = {
            Friends: api.friends,
            Worlds: () => api.worlds(search.value),
            'My Worlds': () => api.myWorlds(id),
            Avatars: () => api.avatars(id),
            Groups: () => api.groups(id),
            Favorites: api.favorites,
            Notifications: api.notifications
        }[currentTab.value];
        const data = await load();
        items.value = Array.isArray(data) ? data : data?.results || data?.data || [];
    }

    async function loadTab() {
        await run(loadItems);
    }

    async function openItem(item) {
        if (currentTab.value === 'Worlds' || currentTab.value === 'My Worlds' || currentTab.value === 'Avatars') {
            const data = await run(() =>
                currentTab.value === 'Avatars' ? api.getAvatar(item.id) : api.getWorld(item.id)
            );
            if (data) {
                selected.value = data;
                editName.value = data.name || '';
                editDescription.value = data.description || '';
                preview.value = '';
            }
        }
    }

    async function saveDetails() {
        const save = currentTab.value === 'Avatars' ? api.saveAvatar : api.saveWorld;
        const data = await run(() =>
            save(selected.value.id, { id: selected.value.id, name: editName.value, description: editDescription.value })
        );
        if (data) selected.value = data;
    }

    async function chooseImage(event) {
        const file = event.target.files?.[0];
        if (!file || !file.type.startsWith('image/') || file.size > 20_000_000) {
            error.value = 'Select an image smaller than 20 MB.';
            return;
        }
        await run(async () => {
            const img = await createImageBitmap(file);
            const canvas = document.createElement('canvas');
            canvas.width = 1200;
            canvas.height = 900;
            const context = canvas.getContext('2d');
            const targetRatio = 4 / 3;
            const sourceRatio = img.width / img.height;
            let sourceWidth = img.width;
            let sourceHeight = img.height;
            let sourceX = 0;
            let sourceY = 0;
            if (sourceRatio > targetRatio) {
                sourceWidth = img.height * targetRatio;
                sourceX = (img.width - sourceWidth) / 2;
            } else if (sourceRatio < targetRatio) {
                sourceHeight = img.width / targetRatio;
                sourceY = (img.height - sourceHeight) / 2;
            }
            context.drawImage(img, sourceX, sourceY, sourceWidth, sourceHeight, 0, 0, canvas.width, canvas.height);
            img.close();
            preview.value = canvas.toDataURL('image/png');
            imageData.value = preview.value.split(',')[1];
        });
        event.target.value = '';
    }

    async function saveImage() {
        const kind = currentTab.value === 'Avatars' ? 'avatar' : 'world';
        const data = await run(() => api.uploadImage(kind, selected.value, imageData.value));
        if (data) {
            selected.value = data;
            imageData.value = '';
            preview.value = '';
        }
    }

    function onBack() {
        if (selected.value) selected.value = null;
        else if (currentTab.value !== 'Friends') switchTab('Friends');
        else invoke('background');
    }

    window.vrcxAndroidBack = onBack;
    const onResume = () => {
        if (document.visibilityState === 'visible' && user.value) loadTab();
    };
    onMounted(() => {
        restore();
        document.addEventListener('visibilitychange', onResume);
    });
    onUnmounted(() => document.removeEventListener('visibilitychange', onResume));
</script>

<style scoped>
    .mobile-shell {
        min-height: 100dvh;
        color: #e9edf3;
        background: #16191e;
        font:
            15px system-ui,
            sans-serif;
    }
    header {
        height: 56px;
        display: flex;
        gap: 12px;
        align-items: center;
        padding: 0 16px;
        background: #242930;
        position: sticky;
        top: 0;
        z-index: 2;
    }
    header small,
    .profile small,
    .item small {
        display: block;
        color: #aab4c1;
        font-size: 11px;
        font-weight: normal;
    }
    .right {
        margin-left: auto;
    }
    main {
        max-width: 780px;
        margin: auto;
        padding: 16px;
        padding-bottom: 56px;
    }
    button,
    input,
    textarea,
    select {
        font: inherit;
    }
    button,
    .picker {
        min-height: 44px;
        border: 0;
        border-radius: 7px;
        padding: 10px 14px;
        color: inherit;
        background: #344b66;
        cursor: pointer;
    }
    button:disabled {
        opacity: 0.5;
    }
    input,
    textarea,
    select {
        min-height: 44px;
        width: 100%;
        border: 1px solid #586474;
        border-radius: 6px;
        padding: 9px;
        background: #242930;
        color: inherit;
    }
    label {
        display: block;
        margin: 12px 0;
    }
    .login {
        max-width: 440px;
        margin: 8vh auto;
    }
    .login button {
        width: 100%;
        margin-top: 10px;
    }
    .secondary {
        background: #39414a;
    }
    .muted,
    .empty {
        color: #aab4c1;
    }
    .error {
        background: #60313b;
        border-radius: 6px;
        padding: 12px;
    }
    nav {
        display: flex;
        overflow-x: auto;
        gap: 6px;
        padding: 14px 0;
    }
    nav button {
        flex: none;
        background: #30353c;
        white-space: nowrap;
    }
    nav button.active {
        background: #42638a;
    }
    .profile,
    .item,
    .search {
        display: flex;
        align-items: center;
        gap: 12px;
    }
    .profile img {
        width: 54px;
        height: 54px;
        border-radius: 50%;
        object-fit: cover;
    }
    .search input {
        min-width: 0;
    }
    .item {
        width: 100%;
        text-align: left;
        background: #242930;
        border-radius: 0;
        border-bottom: 1px solid #343b44;
    }
    .item img {
        width: 56px;
        height: 56px;
        object-fit: cover;
        border-radius: 5px;
    }
    .item span:nth-child(2) {
        min-width: 0;
        flex: 1;
        overflow-wrap: anywhere;
    }
    .item small {
        max-height: 3em;
        overflow: hidden;
    }
    .details > img {
        display: block;
        width: 100%;
        max-height: 280px;
        object-fit: cover;
    }
    .picker {
        display: block;
        background: #39414a;
    }
    .picker input {
        border: 0;
        padding: 5px 0;
    }
    .preview img {
        width: min(100%, 280px);
        display: block;
        margin: 14px 0;
    }
</style>
