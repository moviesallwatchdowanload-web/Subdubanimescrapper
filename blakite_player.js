const baseDURL = "https://hugh.cdn.rumble.cloud/video/";
const qualityCodes = ['oaa', 'baa', 'caa', 'gaa', 'haa'];
const qualityLabels = ['240p', '360p', '480p', '720p', '1080p'];

function initializePlayer(data) {
    // 🔥 COMPLETE DEBUG LOGGING
    console.log('==================== PLAYER DEBUG ====================');
    console.log('🎮 Player Function Called');
    console.log('📦 Input Data:', JSON.stringify(data, null, 2));
    console.log('📦 Data Type:', typeof data);
    console.log('📦 Data Keys:', Object.keys(data));

    // Critical data validation
    if (!data.dataId) {
        console.error('❌ CRITICAL: No dataId provided!');
        console.error('❌ Cannot build video URL without dataId');
        return null;
    }

    console.log('✅ DataId Found:', data.dataId);
    console.log('✅ QID:', data.qid);
    console.log('✅ Quality:', data.quality);
    console.log('✅ Format:', data.format);
    console.log('✅ Ranges:', data.ranges);

    // ✅ FIX: Available qualities ab "qid" (jo sirf ek COUNT hai) se nahi,
    // balki actual "ranges" string se nikaalte hain — kyunki yahi
    // batata hai ki is episode ke liye kaunsi quality REALLY exist karti hai.
    // qid pe bharosa karne se problem ye thi: agar kisi episode mein
    // beech ki koi quality (jaise 480p) missing hoti thi, tab bhi qid=5
    // hone ki wajah se 5 hi options dikhte the, aur missing wali quality
    // ka url bina sahi r_range ke bnta tha -> wo quality fail/glitch karti thi.

    let qualityList = []; // [{ label, code, range }]

    if (data.format === 'M3U8' && data.ranges) {
        // ranges string ko parse karke sirf WAHI qualities lo jo isme
        // actually mention hain
        const rangeLines = data.ranges.split('\n').map(line => line.trim()).filter(line => line);
        const rangeMap = {};
        rangeLines.forEach(line => {
            const match = line.match(/^(\d+-\d+)\s*\(([^)]+)\)/);
            if (match) {
                const range = match[1];
                const label = match[2].trim();
                rangeMap[label] = range;
            }
        });
        console.log('🎯 Parsed Range Map:', rangeMap);

        // canonical order (240p -> 1080p) maintain karo, lekin sirf wahi
        // include karo jo rangeMap mein mile — taaki code<->label mapping
        // hamesha sahi rahe chahe ranges string kisi bhi order mein aaye
        qualityLabels.forEach((label, i) => {
            if (Object.prototype.hasOwnProperty.call(rangeMap, label)) {
                qualityList.push({
                    label: label,
                    code: qualityCodes[i],
                    range: rangeMap[label]
                });
            }
        });

        // safety fallback: agar kisi wajah se ranges parse hi nahi hua
        // (regex match fail, weird format, etc), tab purana qid-based
        // tareeka use karo taaki player crash na ho
        if (qualityList.length === 0) {
            console.warn('⚠️ Ranges string parse nahi hua, qid-based fallback use ho raha hai');
            const maxQualityIndex = Math.min(data.qid || qualityLabels.length, qualityLabels.length) - 1;
            for (let i = 0; i <= maxQualityIndex; i++) {
                qualityList.push({ label: qualityLabels[i], code: qualityCodes[i], range: null });
            }
        }
    } else {
        // MP4 (ya ranges hi nahi hai) ke case mein API humein per-quality
        // availability nahi batata, isliye qid count pe hi depend karna
        // padta hai. NOTE: agar kisi episode ka koi mp4 quality file CDN
        // pe actually missing hai, JS ko pata nahi chal sakta — ideal
        // long-term fix ye hoga ki backend M3U8 ki tarah mp4 ke liye bhi
        // ek explicit available-qualities list bheje.
        const maxQualityIndex = Math.min(data.qid || qualityLabels.length, qualityLabels.length) - 1;
        for (let i = 0; i <= maxQualityIndex; i++) {
            qualityList.push({ label: qualityLabels[i], code: qualityCodes[i], range: null });
        }
    }

    const availableQualities = qualityList.map(q => q.label);
    console.log('🎯 Available Qualities (from ranges):', availableQualities);

    // Default quality set karo
    let defaultQuality;
    if (data.quality && availableQualities.includes(data.quality)) {
        defaultQuality = data.quality;
    } else {
        defaultQuality = availableQualities[availableQualities.length - 1];
    }
    console.log('🎯 Default Quality Selected:', defaultQuality);

    function buildUrl(item) {
        if (data.format === 'M3U8') {
            const range = item.range || '';
            return `${baseDURL}${data.dataId}.${item.code}.tar?r_file=chunklist.m3u8&r_type=application%2Fvnd.apple.mpegurl${range ? `&r_range=${range}` : ''}`;
        }
        return `${baseDURL}${data.dataId}.${item.code}.mp4`;
    }

    const defaultItem = qualityList.find(q => q.label === defaultQuality);
    const videoUrl = buildUrl(defaultItem);

    console.log('🔗 Video URL Construction:');
    console.log('   - Base URL:', baseDURL);
    console.log('   - Data ID:', data.dataId);
    console.log('   - Quality Code:', defaultItem.code);
    console.log('   - Format:', data.format);
    console.log('   - Final URL:', videoUrl);

    // Check if custom videoUrl is provided
    const finalVideoUrl = data.videoUrl || videoUrl;
    console.log('🔗 Final Video URL:', finalVideoUrl);

    // Quality options generation — ab sirf unhi qualities ke liye jo
    // actually available hain, aur har ek ka apna sahi r_range use hoga
    const qualityOptions = qualityList.map(item => {
        const url = buildUrl(item);
        console.log(`   ${item.label}: ${url}`);
        return {
            html: item.label,
            url: url,
            default: item.label === defaultQuality
        };
    });

    console.log('🎯 Quality Options Generated:', qualityOptions.length);

    try {
        const artPlayerInstance = new Artplayer({
            container: data.container || '#artPlayer',
            url: finalVideoUrl,
            type: data.format === 'M3U8' ? 'm3u8' : 'auto',
            theme: '#5865f2',
            autoSize: true,
            fullscreen: true,
            fitContainer: true,
            autoHeight: true,
            autoplay: false,
            playbackRate: true,
            quality: qualityOptions,

            // ✨ CUSTOM CONTROLS WITH MODERN SVG ICONS (BACKWARD -10)
            controls: [
                {
                    name: 'backward',
                    position: 'left',
                    html: `
                        <div style="position: relative; width: 36px; height: 36px; display: flex; align-items: center; justify-content: center;">
                            <svg width="36" height="36" viewBox="0 0 36 36" style="display: block;">
                                <!-- Circular arrow (counter-clockwise) -->
                                <path d="M 18,6 A 12,12 0 1,0 28.39,13.61" 
                                      fill="none" 
                                      stroke="white" 
                                      stroke-width="2.5" 
                                      stroke-linecap="round"/>
                                <!-- Arrow head -->
                                <path d="M 28.39,13.61 L 30,8 L 24,11 Z" 
                                      fill="white"/>
                                <!-- Circle background for number -->
                                <circle cx="18" cy="18" r="8.5" fill="rgba(0,0,0,0.6)"/>
                            </svg>
                            <!-- "-10" text with minus sign -->
                            <span style="position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%); font-size: 10px; font-weight: bold; color: white; font-family: Arial, sans-serif;">-10</span>
                        </div>
                    `,
                    tooltip: 'Backward 10s (J)',
                    style: {
                        color: '#fff',
                    },
                    click: function () {
                        artPlayerInstance.backward = 10;
                        artPlayerInstance.seek = artPlayerInstance.currentTime - 10;
                        console.log('⏪ Skipped backward 10 seconds');
                        showSkipFeedback('backward');
                    },
                },
                {
                    name: 'forward', 
                    position: 'left',
                    html: `
                        <div style="position: relative; width: 36px; height: 36px; display: flex; align-items: center; justify-content: center;">
                            <svg width="36" height="36" viewBox="0 0 36 36" style="display: block;">
                                <!-- Circular arrow (clockwise) -->
                                <path d="M 18,6 A 12,12 0 1,1 7.61,13.61" 
                                      fill="none" 
                                      stroke="white" 
                                      stroke-width="2.5" 
                                      stroke-linecap="round"/>
                                <!-- Arrow head -->
                                <path d="M 7.61,13.61 L 6,8 L 12,11 Z" 
                                      fill="white"/>
                                <!-- Circle background for number -->
                                <circle cx="18" cy="18" r="8.5" fill="rgba(0,0,0,0.6)"/>
                            </svg>
                            <!-- "+10" text with plus sign -->
                            <span style="position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%); font-size: 10px; font-weight: bold; color: white; font-family: Arial, sans-serif;">+10</span>
                        </div>
                    `,
                    tooltip: 'Forward 10s (L)',
                    style: {
                        color: '#fff',
                    },
                    click: function () {
                        artPlayerInstance.forward = 10;
                        artPlayerInstance.seek = artPlayerInstance.currentTime + 10;
                        console.log('⏩ Skipped forward 10 seconds');
                        showSkipFeedback('forward');
                    },
                }
            ],

            settings: [
                {
                    html: 'Quality',
                    tooltip: 'Video Quality',
                    selector: qualityOptions,
                    onSelect: function(item) {
                        console.log('🔄 Quality Changed To:', item.html);
                        console.log('🔄 New URL:', item.url);
                        artPlayerInstance.url = item.url;
                        return item.html;
                    }
                }
            ],
            customType: {
                m3u8: function(video, url) {
                    if (Hls.isSupported()) {
                        const hls = new Hls();
                        hls.loadSource(url);
                        hls.attachMedia(video);
                    } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
                        video.src = url;
                    } else {
                        console.error('❌ HLS not supported in this browser');
                    }
                }
            }
        });

        // ✨ ENHANCED PLAYER EVENT LISTENERS
        artPlayerInstance.on('ready', () => {
            console.log('✅ Player Ready');
            console.log('✅ Current URL:', artPlayerInstance.url);
            setupTitleControlSync(artPlayerInstance);
            setupKeyboardShortcuts(artPlayerInstance);
            styleCustomControls();
            addCustomLoadingSpinner();
        });

        artPlayerInstance.on('error', (error) => {
            console.error('❌ Player Error:', error);
        });

        artPlayerInstance.on('video:loadstart', () => {
            console.log('📹 Video Load Start');
            showCustomLoader();
        });

        artPlayerInstance.on('video:canplay', () => { 
            console.log('📹 Video Can Play');
            hideCustomLoader();
            if (!artPlayerInstance.playing) {
                console.log('📹 Waiting for ad to complete before playing');
            }
        });

        artPlayerInstance.on('video:waiting', () => {
            console.log('⏳ Video Buffering');
            showCustomLoader();
        });

        artPlayerInstance.on('video:playing', () => {
            console.log('🎥 Video Playing After Ad');
            hideCustomLoader();
        });

        artPlayerInstance.on('controls:show', () => {
            console.log('🎮 Controls Shown');
            triggerTitleShow();
        });

        artPlayerInstance.on('controls:hide', () => {
            console.log('🎮 Controls Hidden');
            triggerTitleHide();
        });

        // Force player to fit container
        function resizePlayer() {
            artPlayerInstance.autoSize = true;
            artPlayerInstance.autoHeight = true;
            const player = document.querySelector('.artplayer-app');
            if (player) {
                player.style.width = '100%';
                player.style.height = '100%';
            }
        }

        window.addEventListener('resize', resizePlayer);
        window.addEventListener('load', resizePlayer);

        console.log('✅ Player Instance Created Successfully');
        console.log('==================== END DEBUG ====================');

        return artPlayerInstance;

    } catch (error) {
        console.error('❌ Player Creation Failed:', error);
        console.log('==================== END DEBUG ====================');
        return null;
    }
}

// ✨ ADD CUSTOM LOADING SPINNER TO PLAYER
function addCustomLoadingSpinner() {
    const playerContainer = document.querySelector('.artplayer-app');
    if (!playerContainer) return;

    // Check if loader already exists
    if (document.querySelector('.custom-video-loader')) return;

    const loaderHTML = `
        <div class="custom-video-loader" style="display: none;">
            <div class="loader-spinner">
                <svg width="60" height="60" viewBox="0 0 60 60">
                    <circle cx="30" cy="30" r="25" fill="none" stroke="rgba(255,255,255,0.2)" stroke-width="4"/>
                    <circle cx="30" cy="30" r="25" fill="none" stroke="#5865f2" stroke-width="4" 
                            stroke-dasharray="157" stroke-dashoffset="39.25" 
                            stroke-linecap="round" class="spinner-circle"/>
                </svg>
            </div>
        </div>
    `;

    playerContainer.insertAdjacentHTML('beforeend', loaderHTML);

    // Add CSS for loader animation
    if (!document.querySelector('#customLoaderStyle')) {
        const style = document.createElement('style');
        style.id = 'customLoaderStyle';
        style.textContent = `
            .custom-video-loader {
                position: absolute;
                top: 0;
                left: 0;
                width: 100%;
                height: 100%;
                display: flex;
                align-items: center;
                justify-content: center;
                background: rgba(0, 0, 0, 0.6);
                backdrop-filter: blur(5px);
                z-index: 1001;
                pointer-events: none;
            }

            .loader-spinner {
                animation: loaderFadeIn 0.3s ease-in-out;
            }

            .spinner-circle {
                animation: spinnerRotate 1.5s cubic-bezier(0.4, 0, 0.2, 1) infinite;
                transform-origin: center;
            }

            @keyframes spinnerRotate {
                0% {
                    stroke-dashoffset: 157;
                    transform: rotate(0deg);
                }
                50% {
                    stroke-dashoffset: 39.25;
                    transform: rotate(720deg);
                }
                100% {
                    stroke-dashoffset: 157;
                    transform: rotate(1440deg);
                }
            }

            @keyframes loaderFadeIn {
                from {
                    opacity: 0;
                    transform: scale(0.8);
                }
                to {
                    opacity: 1;
                    transform: scale(1);
                }
            }
        `;
        document.head.appendChild(style);
    }
}

// ✨ SHOW CUSTOM LOADER
function showCustomLoader() {
    const loader = document.querySelector('.custom-video-loader');
    if (loader) {
        loader.style.display = 'flex';
    }
}

// ✨ HIDE CUSTOM LOADER
function hideCustomLoader() {
    const loader = document.querySelector('.custom-video-loader');
    if (loader) {
        loader.style.display = 'none';
    }
}

// ✨ STYLE CUSTOM CONTROLS TO MATCH PLAYER DESIGN
function styleCustomControls() {
    setTimeout(() => {
        const controlsLeft = document.querySelector('.art-controls-left');

        if (controlsLeft) {
            const backwardBtn = controlsLeft.querySelector('[data-index="backward"]');
            const forwardBtn = controlsLeft.querySelector('[data-index="forward"]');

            [backwardBtn, forwardBtn].forEach(btn => {
                if (btn) {
                    btn.style.cssText = `
                        display: flex !important;
                        align-items: center !important;
                        justify-content: center !important;
                        width: 44px !important;
                        height: 44px !important;
                        margin: 0 6px !important;
                        padding: 0 !important;
                        border-radius: 50% !important;
                        background: rgba(255, 255, 255, 0.1) !important;
                        backdrop-filter: blur(10px) !important;
                        transition: all 0.3s ease !important;
                        cursor: pointer !important;
                        border: 1px solid rgba(255, 255, 255, 0.2) !important;
                    `;

                    btn.addEventListener('mouseenter', () => {
                        btn.style.background = 'rgba(255, 255, 255, 0.2)';
                        btn.style.transform = 'scale(1.1)';
                        btn.style.borderColor = 'rgba(255, 255, 255, 0.4)';
                    });

                    btn.addEventListener('mouseleave', () => {
                        btn.style.background = 'rgba(255, 255, 255, 0.1)';
                        btn.style.transform = 'scale(1)';
                        btn.style.borderColor = 'rgba(255, 255, 255, 0.2)';
                    });
                }
            });
        }
    }, 500);
}

// ✨ ENHANCED SKIP FEEDBACK DISPLAY WITH SMOOTH ANIMATION
function showSkipFeedback(direction) {
    // Remove existing feedback
    const existingFeedback = document.querySelector('.skip-feedback-icon');
    if (existingFeedback) {
        existingFeedback.remove();
    }

    // Determine if backward or forward
    const isBackward = direction === 'backward';

    // Create new feedback with icon
    const feedback = document.createElement('div');
    feedback.className = 'skip-feedback-icon';

    // Create SVG icon based on direction with -10 or +10
    const svgIcon = isBackward ? `
        <svg width="80" height="80" viewBox="0 0 80 80" style="filter: drop-shadow(0 4px 12px rgba(0,0,0,0.3));">
            <circle cx="40" cy="40" r="35" fill="rgba(0, 0, 0, 0.75)" stroke="white" stroke-width="3"/>
            <path d="M 40,15 A 25,25 0 1,0 61.65,30.35" 
                  fill="none" 
                  stroke="white" 
                  stroke-width="4" 
                  stroke-linecap="round"/>
            <path d="M 61.65,30.35 L 65,20 L 55,26 Z" fill="white"/>
            <circle cx="40" cy="40" r="16" fill="rgba(0,0,0,0.5)"/>
            <text x="40" y="48" font-family="Arial, sans-serif" font-size="20" font-weight="bold" fill="white" text-anchor="middle">-10</text>
        </svg>
    ` : `
        <svg width="80" height="80" viewBox="0 0 80 80" style="filter: drop-shadow(0 4px 12px rgba(0,0,0,0.3));">
            <circle cx="40" cy="40" r="35" fill="rgba(0, 0, 0, 0.75)" stroke="white" stroke-width="3"/>
            <path d="M 40,15 A 25,25 0 1,1 18.35,30.35" 
                  fill="none" 
                  stroke="white" 
                  stroke-width="4" 
                  stroke-linecap="round"/>
            <path d="M 18.35,30.35 L 15,20 L 25,26 Z" fill="white"/>
            <circle cx="40" cy="40" r="16" fill="rgba(0,0,0,0.5)"/>
            <text x="40" y="48" font-family="Arial, sans-serif" font-size="20" font-weight="bold" fill="white" text-anchor="middle">+10</text>
        </svg>
    `;

    feedback.innerHTML = svgIcon;
    feedback.style.cssText = `
        position: absolute;
        top: 50%;
        left: 50%;
        transform: translate(-50%, -50%);
        z-index: 1002;
        pointer-events: none;
        animation: smoothSkipFeedback 0.8s cubic-bezier(0.34, 1.56, 0.64, 1);
    `;

    // Add CSS animation if not exists
    if (!document.querySelector('#skipFeedbackStyle')) {
        const style = document.createElement('style');
        style.id = 'skipFeedbackStyle';
        style.textContent = `
            @keyframes smoothSkipFeedback {
                0% {
                    opacity: 0;
                    transform: translate(-50%, -50%) scale(0.5) rotate(0deg);
                }
                30% {
                    opacity: 1;
                    transform: translate(-50%, -50%) scale(1.15) rotate(${isBackward ? '-15deg' : '15deg'});
                }
                60% {
                    opacity: 1;
                    transform: translate(-50%, -50%) scale(1) rotate(0deg);
                }
                100% {
                    opacity: 0;
                    transform: translate(-50%, -50%) scale(0.8) rotate(0deg);
                }
            }
        `;
        document.head.appendChild(style);
    }

    const playerContainer = document.querySelector('.artplayer-app');
    if (playerContainer) {
        playerContainer.appendChild(feedback);
        setTimeout(() => {
            if (feedback.parentNode) {
                feedback.remove();
            }
        }, 800);
    }
}

// ✨ KEYBOARD SHORTCUTS FOR SKIP BUTTONS
function setupKeyboardShortcuts(playerInstance) {
    document.addEventListener('keydown', (event) => {
        const playerContainer = document.querySelector('.artplayer-app');
        if (!playerContainer || document.activeElement === document.body) {
            switch (event.key) {
                case 'ArrowLeft':
                    event.preventDefault();
                    playerInstance.seek = playerInstance.currentTime - 10;
                    showSkipFeedback('backward');
                    console.log('⏪ Keyboard: Skipped backward 10 seconds');
                    break;

                case 'ArrowRight':
                    event.preventDefault();
                    playerInstance.seek = playerInstance.currentTime + 10;
                    showSkipFeedback('forward');
                    console.log('⏩ Keyboard: Skipped forward 10 seconds');
                    break;

                case 'j':
                case 'J':
                    event.preventDefault();
                    playerInstance.seek = playerInstance.currentTime - 10;
                    showSkipFeedback('backward');
                    console.log('⏪ J Key: Skipped backward 10 seconds');
                    break;

                case 'l':
                case 'L':
                    event.preventDefault();
                    playerInstance.seek = playerInstance.currentTime + 10;
                    showSkipFeedback('forward');
                    console.log('⏩ L Key: Skipped forward 10 seconds');
                    break;
            }
        }
    });
}

// ✨ TITLE-CONTROLS SYNC FUNCTIONS
function setupTitleControlSync(playerInstance) {
    console.log('🔗 Setting up title-controls synchronization');
    window.currentPlayerInstance = playerInstance;
}

function triggerTitleShow() {
    const titleOverlay = document.getElementById('titleOverlay');
    if (titleOverlay) {
        titleOverlay.style.opacity = '1';
        titleOverlay.style.transform = 'translateY(0)';
    }
}

function triggerTitleHide() {
    const titleOverlay = document.getElementById('titleOverlay');
    if (titleOverlay) {
        titleOverlay.style.opacity = '0';
        titleOverlay.style.transform = 'translateY(-10px)';
    }
}

// ✨ QUALITY CHANGE FUNCTION FOR EXTERNAL USE
function changePlayerQuality(quality) {
    if (window.currentPlayerInstance) {
        const qualitySelector = window.currentPlayerInstance.quality;
        if (qualitySelector && qualitySelector.length > 0) {
            const targetQuality = qualitySelector.find(q => q.html === quality);
            if (targetQuality) {
                window.currentPlayerInstance.url = targetQuality.url;
                return true;
            }
        }
    }
    return false;
}

// ✨ EXTERNAL SKIP FUNCTIONS
function skipBackward() {
    if (window.currentPlayerInstance) {
        window.currentPlayerInstance.seek = window.currentPlayerInstance.currentTime - 10;
        showSkipFeedback('backward');
        console.log('⏪ External: Skipped backward 10 seconds');
        return true;
    }
    return false;
}

function skipForward() {
    if (window.currentPlayerInstance) {
        window.currentPlayerInstance.seek = window.currentPlayerInstance.currentTime + 10;
        showSkipFeedback('forward');
        console.log('⏩ External: Skipped forward 10 seconds');
        return true;
    }
    return false;
}