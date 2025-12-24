const https = require('https');
const http = require('http');
const fs = require('fs');
const path = require('path');

const VIDEO_URL = 'https://www.tiktok.com/@fromommy/video/7584945772966186253';

// Browser-like headers
const HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8',
    'Accept-Language': 'en-US,en;q=0.9',
    'Accept-Encoding': 'gzip, deflate, br',
    'Connection': 'keep-alive',
    'Upgrade-Insecure-Requests': '1',
    'Sec-Fetch-Dest': 'document',
    'Sec-Fetch-Mode': 'navigate',
    'Sec-Fetch-Site': 'none',
    'Sec-Fetch-User': '?1',
    'Cache-Control': 'max-age=0',
};

// Store cookies from page response
let pageCookies = '';

function fetch(url, options = {}) {
    return new Promise((resolve, reject) => {
        const urlObj = new URL(url);
        const protocol = urlObj.protocol === 'https:' ? https : http;

        const reqOptions = {
            hostname: urlObj.hostname,
            port: urlObj.port || (urlObj.protocol === 'https:' ? 443 : 80),
            path: urlObj.pathname + urlObj.search,
            method: options.method || 'GET',
            headers: { ...HEADERS, ...options.headers },
        };

        const req = protocol.request(reqOptions, (res) => {
            // Store cookies
            if (res.headers['set-cookie']) {
                pageCookies = res.headers['set-cookie']
                    .map(c => c.split(';')[0])
                    .join('; ');
                console.log('Captured cookies:', pageCookies.substring(0, 80) + '...');
            }

            // Handle redirects
            if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
                console.log(`Redirecting to: ${res.headers.location}`);
                return resolve(fetch(res.headers.location, options));
            }

            const chunks = [];

            // Handle gzip
            let stream = res;
            if (res.headers['content-encoding'] === 'gzip') {
                const zlib = require('zlib');
                stream = res.pipe(zlib.createGunzip());
            } else if (res.headers['content-encoding'] === 'br') {
                const zlib = require('zlib');
                stream = res.pipe(zlib.createBrotliDecompress());
            } else if (res.headers['content-encoding'] === 'deflate') {
                const zlib = require('zlib');
                stream = res.pipe(zlib.createInflate());
            }

            stream.on('data', (chunk) => chunks.push(chunk));
            stream.on('end', () => {
                const body = Buffer.concat(chunks).toString('utf8');
                resolve({
                    status: res.statusCode,
                    headers: res.headers,
                    body,
                    url: url,
                });
            });
            stream.on('error', reject);
        });

        req.on('error', reject);
        req.end();
    });
}

function extractVideoData(html) {
    // Try __UNIVERSAL_DATA_FOR_REHYDRATION__
    const universalMatch = html.match(/<script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application\/json">(.+?)<\/script>/s);
    if (universalMatch) {
        try {
            const data = JSON.parse(universalMatch[1]);
            console.log('Found __UNIVERSAL_DATA_FOR_REHYDRATION__ data');
            return data;
        } catch (e) {
            console.log('Failed to parse universal data:', e.message);
        }
    }

    // Try SIGI_STATE
    const sigiMatch = html.match(/<script id="SIGI_STATE" type="application\/json">(.+?)<\/script>/s);
    if (sigiMatch) {
        try {
            const data = JSON.parse(sigiMatch[1]);
            console.log('Found SIGI_STATE data');
            return data;
        } catch (e) {
            console.log('Failed to parse SIGI_STATE:', e.message);
        }
    }

    return null;
}

function findVideoUrls(obj, urls = [], depth = 0) {
    if (depth > 20) return urls;
    if (!obj || typeof obj !== 'object') return urls;

    for (const key of Object.keys(obj)) {
        const value = obj[key];

        // Priority 1: downloadAddr (full video with audio)
        if (key === 'downloadAddr') {
            if (typeof value === 'string' && value.startsWith('http')) {
                urls.push({ key, url: value, priority: 1 });
            }
        }
        // Priority 2: playAddr (video stream)
        else if (key === 'playAddr') {
            if (typeof value === 'string' && value.startsWith('http')) {
                urls.push({ key, url: value, priority: 2 });
            }
        }
        // Priority 3: playUrl (often audio-only)
        else if (key === 'playUrl') {
            if (typeof value === 'string' && value.startsWith('http')) {
                urls.push({ key, url: value, priority: 3 });
            }
        }
        // Also look for video objects with url properties
        else if (key === 'video' && typeof value === 'object' && value !== null) {
            if (value.downloadAddr && typeof value.downloadAddr === 'string') {
                urls.push({ key: 'video.downloadAddr', url: value.downloadAddr, priority: 1 });
            }
            if (value.playAddr && typeof value.playAddr === 'string') {
                urls.push({ key: 'video.playAddr', url: value.playAddr, priority: 2 });
            }
            if (value.playUrl && typeof value.playUrl === 'string') {
                urls.push({ key: 'video.playUrl', url: value.playUrl, priority: 3 });
            }
        }
        // Recurse
        else if (Array.isArray(value)) {
            for (const item of value) {
                findVideoUrls(item, urls, depth + 1);
            }
        } else if (typeof value === 'object') {
            findVideoUrls(value, urls, depth + 1);
        }
    }

    return urls;
}

function downloadFile(url, filename, cookies = '') {
    return new Promise((resolve, reject) => {
        console.log(`\nTrying: ${url.substring(0, 80)}...`);

        const urlObj = new URL(url);
        const protocol = urlObj.protocol === 'https:' ? https : http;

        const headers = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
            'Referer': 'https://www.tiktok.com/',
            'Origin': 'https://www.tiktok.com',
            'Accept': '*/*',
            'Accept-Language': 'en-US,en;q=0.9',
            'Sec-Fetch-Dest': 'video',
            'Sec-Fetch-Mode': 'cors',
            'Sec-Fetch-Site': 'cross-site',
            'Range': 'bytes=0-',
        };

        if (cookies) {
            headers['Cookie'] = cookies;
        }

        const reqOptions = {
            hostname: urlObj.hostname,
            port: urlObj.port || (urlObj.protocol === 'https:' ? 443 : 80),
            path: urlObj.pathname + urlObj.search,
            method: 'GET',
            headers,
        };

        const req = protocol.request(reqOptions, (res) => {
            // Follow redirects
            if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
                console.log(`  Redirect -> ${res.headers.location.substring(0, 60)}...`);
                return resolve(downloadFile(res.headers.location, filename, cookies));
            }

            // Accept 200 or 206 (partial content)
            if (res.statusCode !== 200 && res.statusCode !== 206) {
                return reject(new Error(`HTTP ${res.statusCode}`));
            }

            console.log(`  Status: ${res.statusCode}, Content-Type: ${res.headers['content-type']}`);

            const file = fs.createWriteStream(filename);
            let downloaded = 0;
            const contentLength = parseInt(res.headers['content-length'], 10);

            res.on('data', (chunk) => {
                downloaded += chunk.length;
                if (contentLength) {
                    const percent = ((downloaded / contentLength) * 100).toFixed(1);
                    process.stdout.write(`\r  Progress: ${percent}% (${(downloaded / 1024 / 1024).toFixed(2)} MB)`);
                } else {
                    process.stdout.write(`\r  Downloaded: ${(downloaded / 1024 / 1024).toFixed(2)} MB`);
                }
            });

            res.pipe(file);

            file.on('finish', () => {
                console.log(`\n  Saved to: ${filename}`);
                file.close();
                resolve({ success: true, filename, size: downloaded });
            });

            file.on('error', (err) => {
                fs.unlink(filename, () => {});
                reject(err);
            });
        });

        req.on('error', reject);
        req.end();
    });
}

async function tryDownload(urls, filename) {
    for (const urlInfo of urls) {
        try {
            console.log(`\nAttempt [${urlInfo.key}]:`);
            const result = await downloadFile(urlInfo.url, filename, pageCookies);

            // Verify it's a valid video (not HTML error page)
            const stats = fs.statSync(filename);
            if (stats.size < 10000) {
                const content = fs.readFileSync(filename, 'utf8');
                if (content.includes('<html') || content.includes('<!DOCTYPE')) {
                    console.log('  Got HTML instead of video, trying next URL...');
                    continue;
                }
            }

            return result;
        } catch (error) {
            console.log(`  Failed: ${error.message}`);
        }
    }
    throw new Error('All download URLs failed');
}

async function main() {
    console.log('TikTok Video Downloader');
    console.log('=======================');
    console.log(`Target: ${VIDEO_URL}\n`);

    try {
        // Step 1: Fetch the page
        console.log('Step 1: Fetching TikTok page...');
        const response = await fetch(VIDEO_URL);
        console.log(`Status: ${response.status}`);
        console.log(`Content length: ${response.body.length} bytes\n`);

        // Save HTML for debugging
        fs.writeFileSync('page.html', response.body);

        // Step 2: Extract video data
        console.log('Step 2: Extracting video data...');
        const data = extractVideoData(response.body);

        if (!data) {
            console.log('Could not find embedded JSON data');
            return;
        }

        // Save JSON for debugging
        fs.writeFileSync('data.json', JSON.stringify(data, null, 2));

        // Step 3: Find video URLs
        console.log('\nStep 3: Finding video URLs...');
        const videoUrls = findVideoUrls(data);

        if (videoUrls.length === 0) {
            console.log('No video URLs found in data');
            return;
        }

        // Sort by priority (1 is highest)
        videoUrls.sort((a, b) => (a.priority || 99) - (b.priority || 99));

        // Remove duplicates
        const seen = new Set();
        const uniqueUrls = videoUrls.filter(v => {
            if (seen.has(v.url)) return false;
            seen.add(v.url);
            return true;
        });

        console.log(`Found ${uniqueUrls.length} unique video URLs:`);
        for (const v of uniqueUrls) {
            console.log(`  [${v.key}] (priority ${v.priority}) ${v.url.substring(0, 60)}...`);
        }

        // Step 4: Try downloading
        console.log('\nStep 4: Downloading video...');
        const filename = path.join(__dirname, 'tiktok_video.mp4');

        // Clean up old file
        if (fs.existsSync(filename)) {
            fs.unlinkSync(filename);
        }

        const result = await tryDownload(uniqueUrls, filename);

        // Verify the file
        const stats = fs.statSync(filename);
        console.log(`\n✓ Download complete! File size: ${(stats.size / 1024 / 1024).toFixed(2)} MB`);

    } catch (error) {
        console.error('\nError:', error.message);
    }
}

main();
