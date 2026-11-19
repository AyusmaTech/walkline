import { execFile } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { promisify } from 'node:util';
import { setTimeout as delay } from 'node:timers/promises';

// Read-only GPU verification of the bundled debug scene. No API token is needed.
const execute = promisify(execFile);
const args = process.argv.slice(2);
const option = name => { const i = args.indexOf(name); return i < 0 ? undefined : args[i + 1]; };
let adb, serial, port, socket;
try {
  serial = option('--serial') ?? 'emulator-5554';
  const duration = Number(option('--watch-seconds') ?? 0);
  if (!/^emulator-\d+$/.test(serial)) throw new Error('Choose an Android emulator serial.');
  if (!Number.isFinite(duration) || duration < 0 || duration > 300) throw new Error('Use a watch duration from 0 to 300 seconds.');
  const properties = await readFile(new URL('../../android/local.properties', import.meta.url), 'utf8').catch(() => '');
  const sdk = process.env.ANDROID_HOME ?? properties.split(/\r?\n/).find(line => line.startsWith('sdk.dir='))?.slice(8) ?? join(homedir(), 'Library/Android/sdk');
  adb = join(sdk, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
  const run = (...commands) => execute(adb, ['-s', serial, ...commands], { timeout: 10_000 });
  const { stdout: processId } = await run('shell', 'pidof', 'com.tobenamed.app');
  if (!/^\d+$/.test(processId.trim())) throw new Error('Open ToBeNamed and select its 3D map first.');
  port = (await run('forward', 'tcp:0', `localabstract:webview_devtools_remote_${processId.trim()}`)).stdout.trim();
  if (!/^\d+$/.test(port)) throw new Error('Could not forward the debug WebView port.');
  const tabs = await fetch(`http://127.0.0.1:${port}/json`).then(r => r.json());
  const tab = tabs.find(t => t.url === 'https://appassets.androidplatform.net/navigation3d/index.html');
  if (!tab) throw new Error('The bundled WebGL scene is not open. Select 3D Map in a debug build; native fallback does not count as a WebGL pass.');
  socket = new WebSocket(tab.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => {
    socket.addEventListener('open', resolve, { once: true });
    socket.addEventListener('error', reject, { once: true });
  });
  let sequence = 0;
  const pending = new Map();
  socket.addEventListener('message', e => {
    const message = JSON.parse(e.data);
    if (message.id) { pending.get(message.id)?.(message); pending.delete(message.id); }
  });
  const evaluate = expression => new Promise((resolve, reject) => {
    const id = ++sequence, timeout = setTimeout(() => reject(new Error('GPU inspection timed out.')), 5000);
    pending.set(id, message => {
      clearTimeout(timeout);
      if (message.error || message.result?.exceptionDetails) reject(new Error('Could not inspect the bundled scene.'));
      else resolve(message.result.result.value);
    });
    socket.send(JSON.stringify({ id, method: 'Runtime.evaluate', params: { expression, returnByValue: true } }));
  });
  const probe = `(()=>{
    const state=window.navigation3d?.inspect(),canvas=document.querySelector('.maplibregl-canvas');
    if(!state?.drawReady||!canvas)return {valid:false,state};
    const gl=canvas.getContext('webgl2');
    if(!gl||gl.isContextLost()||gl.getParameter(gl.FRAMEBUFFER_BINDING)!==null)return {valid:false,state};
    const colors=new Set();let visible=0;
    for(const x of [.25,.5,.75])for(const y of [.25,.5,.75]){
      const pixel=new Uint8Array(4);
      gl.readPixels(Math.floor(canvas.width*x),Math.floor(canvas.height*y),1,1,gl.RGBA,gl.UNSIGNED_BYTE,pixel);
      if(pixel[3]>100){visible++;colors.add(Array.from(pixel).join(','));}
    }
    const buildingSourceFeatures=map.querySourceFeatures('city-bodies').length;
    return {valid:state.loaded&&state.cityBuildings>0&&buildingSourceFeatures>0&&visible>=3&&colors.size>=2,
      visibleSamples:visible,distinctColors:colors.size,buildingSourceFeatures,state};
  })()`;
  let result;
  for (let i = 0; i < 40; i++) {
    result = await evaluate(probe);
    if (result.valid) break;
    await delay(500);
  }
  if (!result.valid) throw new Error('The scene did not produce a verified, non-uniform GPU frame.');
  console.log('PASS: bundled WebGL scene has visible map pixels, loaded buildings, and verified readiness.');
  console.log(JSON.stringify(result));
  const until = performance.now() + duration * 1000;
  while (performance.now() < until) {
    await delay(Math.min(2000, until - performance.now()));
    result = await evaluate(probe);
    if (!result.valid) throw new Error('The WebGL scene stopped producing a valid frame during the watch.');
  }
  if (duration) console.log(`PASS: scene stayed rendered for ${duration} seconds.`);
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
} finally {
  socket?.close();
  if (adb && port) await execute(adb, ['-s', serial, 'forward', '--remove', `tcp:${port}`], { timeout: 5000 }).catch(() => {});
}
