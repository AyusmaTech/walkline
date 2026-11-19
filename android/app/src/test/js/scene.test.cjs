const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');
const sandbox={window:{maplibregl:{}}};
vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../../main/assets/navigation3d/scene.js'),'utf8'),sandbox);
const center=[-87.79,41.89];
function building(lng,lat,multi=false){const ring=[[lng,lat],[lng+.0001,lat],[lng+.0001,lat+.0001],[lng,lat]];return {type:'Feature',id:lng,properties:{},geometry:{type:multi?'MultiPolygon':'Polygon',coordinates:multi?[[ring]]:[ring]}};}
test('nearby building details survive when distant tiles are enumerated first',()=>{
 const far=Array.from({length:800},(_,i)=>building(-87.5+i*.0001,42));
 const near=building(center[0]+.0001,center[1]);
 const result=sandbox.nearbyBuildings([...far,near],center);
 assert.equal(result.length,1);assert.equal(result[0],near);
});
test('detail batches stay bounded and nearest-first across polygon types',()=>{
 const features=Array.from({length:400},(_,i)=>building(center[0]+i*.000001,center[1],i%2===0)).reverse();
 const result=sandbox.nearbyBuildings(features,center);
 assert.equal(result.length,300);assert.equal(result[0].id,features.at(-1).id);
 assert.equal(result[0].geometry.type,'Polygon');
 assert.ok(result.every(f=>Number.isFinite(f.id)));
});
test('unsupported or malformed tile geometry cannot enter a detail batch',()=>{
 const bad=[{geometry:null},{geometry:{type:'Point',coordinates:center}},{geometry:{type:'Polygon',coordinates:[]}},building(NaN,41.89)];
 assert.equal(sandbox.nearbyBuildings(bad,center).length,0);
});
test('nearby parts survive when the first polygon of a grouped tile is far away',()=>{
 const far=building(-87.5,42),near=building(center[0]+.0001,center[1]);
 const grouped={type:'Feature',id:55,properties:{render_height:9},geometry:{type:'MultiPolygon',coordinates:[far.geometry.coordinates,near.geometry.coordinates]}};
 const result=sandbox.nearbyBuildings([grouped],center);
 assert.equal(result.length,1);
 assert.equal(result[0].geometry.type,'Polygon');
 assert.equal(result[0].id,55);
 assert.equal(result[0].properties.render_height,9);
 assert.deepEqual(result[0].geometry.coordinates,near.geometry.coordinates);
});
test('a large grouped feature is limited by its nearest polygon parts',()=>{
 const polygons=Array.from({length:600},(_,i)=>building(center[0]+i*.000001,center[1]).geometry.coordinates).reverse();
 const result=sandbox.nearbyBuildings([{type:'Feature',properties:{},geometry:{type:'MultiPolygon',coordinates:polygons}}],center);
 assert.equal(result.length,300);
 assert.deepEqual(result[0].geometry.coordinates,polygons.at(-1));
});
test('grouped buildings receive independent IDs for selective cutaways',()=>{
 const a=building(center[0],center[1]),b=building(center[0]+.0002,center[1]);
 const feature={type:'Feature',id:55,properties:{render_height:9,render_min_height:2},geometry:{type:'MultiPolygon',coordinates:[a.geometry.coordinates,b.geometry.coordinates]}};
 const result=sandbox.cityBuildings([feature],center);
 assert.equal(result.length,2);assert.notEqual(result[0].id,result[1].id);
 assert.equal(result[0].properties.sourceId,'55');
 assert.equal(result[0].properties.top,9);assert.equal(result[0].properties.base,2);
 assert.equal(sandbox.cityBuildings([feature],center)[0].id,result[0].id);
});
test('city bodies deduplicate tile repeats and preserve courtyards and mapped heights',()=>{
 const feature=building(center[0],center[1]);feature.properties={render_height:14,render_min_height:3};
 const hole=building(center[0]+.00002,center[1]+.00002).geometry.coordinates[0];feature.geometry.coordinates.push(hole);
 const hidden={...building(center[0]+.0002,center[1]),properties:{hide_3d:true}};
 const result=sandbox.cityBuildings([feature,feature,hidden],center);
 assert.equal(result.length,1);assert.equal(result[0].properties.top,14);assert.equal(result[0].properties.base,3);
 assert.deepEqual(result[0].geometry.coordinates[1],hole);
});
test('rendered-feature tile parser buffers cannot enter city GeoJSON',()=>{
 const feature=building(center[0],center[1],true);
 feature._vectorTileFeature={_pbf:{buf:new Uint8Array(200_000)}};
 const result=sandbox.cityBuildings([feature],center);
 assert.equal(result.length,1);
 assert.deepEqual(Object.keys(result[0]).sort(),['geometry','id','properties','type']);
 assert.ok(JSON.stringify(result).length<1000);
});
function boxBody(x1,y1,x2,y2,top=12,base=0){return {geometry:{type:'Polygon',coordinates:[[[x1,y1],[x2,y1],[x2,y2],[x1,y2],[x1,y1]]]},properties:{top,base}};}
test('a building fades only when the camera-to-walker ray crosses its height span',()=>{
 const body=boxBody(-.00005,.0007,.00005,.0009);
 assert.equal(sandbox.buildingObstructs([0,0],30,[0,.001],body),true);
 body.properties.top=1;
 assert.equal(sandbox.buildingObstructs([0,0],30,[0,.001],body),false);
 body.properties.top=30;body.properties.base=15;
 assert.equal(sandbox.buildingObstructs([0,0],30,[0,.001],body),false);
});
test('unrelated neighboring buildings stay solid',()=>{
 assert.equal(sandbox.buildingObstructs([0,0],30,[0,.001],boxBody(.0002,.0007,.0004,.0009)),false);
});
test('a courtyard does not count as an obstructing wall',()=>{
 const body=boxBody(-.0002,.0002,.0002,.0008,12);
 body.geometry.coordinates.push([[-.0001,.0003],[.0001,.0003],[.0001,.0007],[-.0001,.0007],[-.0001,.0003]]);
 assert.equal(sandbox.buildingObstructs([0,.0004],5,[0,.0006],body),false);
});
test('a camera inside a tall building can recover visibility with a cutaway',()=>{
 assert.equal(sandbox.buildingObstructs([0,0],30,[0,.001],boxBody(-.0002,-.0002,.0002,.0002,80)),true);
});
function separation(a,b){
 const r=Math.PI/180,dlat=(b[1]-a[1])*r,dlng=(b[0]-a[0])*r;
 return 12742017.6*Math.asin(Math.sqrt(Math.sin(dlat/2)**2+Math.cos(a[1]*r)*Math.cos(b[1]*r)*Math.sin(dlng/2)**2));
}
const walkingCamera={walking:true,follow:true,arrived:false,picking:false,guiding:true,cameraTurn:0,walker:center,street:[center[0]+.0002,center[1]],focus:center,bearing:0,topPadding:240,viewportHeight:688};
test('street-level framing keeps human eye height through turns instead of lifting above roofs',()=>{
 const straight=sandbox.eyeView(walkingCamera),corner=sandbox.eyeView({...walkingCamera,cameraTurn:1});
 assert.equal(straight.height,1.7);assert.equal(corner.height,straight.height);
 assert.ok(straight.pitch>=84&&corner.pitch>=84);
 assert.ok(separation(straight.eye,walkingCamera.walker)<.001);
 assert.deepEqual(corner.eye,straight.eye);
 assert.equal(straight.eye[1],center[1]);assert.ok(straight.ground[1]>center[1]);
 assert.ok(separation(straight.eye,straight.ground)>separation(corner.eye,corner.ground));
 assert.deepEqual(walkingCamera.walker,center);
});
test('camera solver receives eye-height poses and explicit zero roll without aerial padding',()=>{
 const calls=[],solver={calculateCameraOptionsFromTo(...args){calls.push(args);return {center:args[2],elevation:0,zoom:20.7,pitch:85,bearing:0};}};
 const walking=sandbox.cameraOptions(walkingCamera,solver);
 const arrived=sandbox.cameraOptions({...walkingCamera,arrived:true},solver);
 const preview=sandbox.cameraOptions({...walkingCamera,walking:false},solver);
 assert.equal(calls.length,2);assert.equal(calls[0][1],1.7);assert.equal(calls[1][1],1.7);assert.equal(calls[0][3],0);
 assert.ok(separation(calls[1][0],walkingCamera.walker)<.001);
 assert.equal(walking.roll,0);assert.equal(walking.padding.top,0);assert.equal(walking.padding.bottom,0);
 assert.ok(arrived.duration>walking.duration);assert.ok(preview.padding.top>walking.padding.top);
});
test('missing GPS and street-picking never invent a walking viewpoint',()=>{
 assert.equal(sandbox.eyeView({...walkingCamera,walker:null}),null);
 assert.equal(sandbox.eyeView({...walkingCamera,picking:true}),null);
 assert.equal(sandbox.eyeView({...walkingCamera,walking:false}),null);
 const normal=sandbox.eyeView(walkingCamera);
 const uncertain=sandbox.eyeView({...walkingCamera,cameraTurn:1,guiding:false});
 assert.equal(normal.pitch,uncertain.pitch);
 for(const cameraTurn of [NaN,Infinity,-2,4])assert.ok(Number.isFinite(sandbox.eyeView({...walkingCamera,cameraTurn}).pitch));
});
function frameHarness({transparent=false,baseReady=true}={}){
 let time=2000,reads=0,id=0,recoveries=0,ready=0;
 const timers=new Map();
 const gl={FRAMEBUFFER_BINDING:1,RGBA:2,UNSIGNED_BYTE:3,isContextLost:()=>false,getParameter:()=>null,
  readPixels(x,y,w,h,format,type,pixel){reads++;pixel.set(transparent?[0,0,0,0]:reads%2?[20,29,49,255]:[230,255,251,255]);}};
 const scene=vm.createContext({window:{maplibregl:{},NativeScene:{ready(){ready++;}}},
  performance:{now:()=>time},setTimeout(fn,delay){timers.set(++id,{fn,delay});return id;},clearTimeout(id){timers.delete(id);},
  recoverTest(){recoveries++;},testMap:{getCanvas:()=>({width:400,height:700,clientWidth:400,clientHeight:700,getContext:()=>gl}),getPixelRatio:()=>1,
   project:()=>({x:200,y:500}),areTilesLoaded:()=>false,triggerRepaint(){}}});
 vm.runInContext(fs.readFileSync(path.join(__dirname,'../../main/assets/navigation3d/scene.js'),'utf8'),scene);
 vm.runInContext(`map=testMap;loaded=true;baseTileReady=${baseReady};state={walking:false,destination:[0,0]};recoverScene=recoverTest;`,scene);
 return {scene,check(){time+=1001;vm.runInContext('verifyFrame()',scene);},get ready(){return ready;},get recoveries(){return recoveries;},
  tick(){time+=1001;const entry=[...timers].find(([,timer])=>timer.delay===1000);assert.ok(entry);timers.delete(entry[0]);entry[1].fn();}};
}
test('pending horizon tiles cannot block useful-frame readiness',()=>{
 const h=frameHarness();h.check();assert.equal(h.ready,1);assert.equal(h.recoveries,0);
});
test('readiness still requires at least one real base tile',()=>{
 const h=frameHarness({baseReady:false});h.check();assert.equal(h.ready,0);
});
test('transparent-frame recovery runs even with pending tiles and an off-screen marker',()=>{
 const h=frameHarness({transparent:true,baseReady:false});
 h.scene.testMap.project=()=>({x:-200,y:-200});
 for(let i=0;i<3;i++)h.check();
 assert.equal(h.recoveries,1);assert.equal(h.ready,0);
});
test('idle health checks detect a blank buffer without new render events',()=>{
 const h=frameHarness({transparent:true});vm.runInContext('drawReady=true;armFrameCheck()',h.scene);
 for(let i=0;i<3;i++)h.tick();assert.equal(h.recoveries,1);
});
test('street-height camera follows the interpolated marker, and browsing stops camera motion immediately',()=>{
 const frames=[],sources=new Map(),poses=[];
 const scene=vm.createContext({window:{maplibregl:{}},performance:{now:()=>0},requestAnimationFrame(fn){frames.push(fn);return frames.length;},cancelAnimationFrame(){},
  initial:walkingCamera,initialPoint:sandbox.offset(center,180,2),testMap:{getBearing:()=>0,
   getSource(name){return {setData(data){sources.set(name,data);}};},
   calculateCameraOptionsFromTo(eye,height,ground){poses.push({eye,height,ground});return {center:ground,zoom:20.8,pitch:85};},jumpTo(){}}});
 vm.runInContext(fs.readFileSync(path.join(__dirname,'../../main/assets/navigation3d/scene.js'),'utf8'),scene);
 vm.runInContext('map=testMap;state={...initial,bearing:90,future:[initial.walker,offset(initial.walker,90,20)]};lastWalker=initialPoint;movePuck(state.walker,90)',scene);
 frames.shift()(500);
 const puck=sources.get('walker').features[0].geometry.coordinates;
 assert.equal(sources.has('connector'),false);
 assert.ok(separation(poses[0].eye,puck)<.001);
 assert.ok(separation(puck,center)>.9&&separation(puck,center)<1.1);
 assert.deepEqual(vm.runInContext('state.future[0]',scene),center);
 vm.runInContext('state.follow=false',scene);frames.shift()(1000);
 assert.equal(poses.length,1);assert.ok(separation(sources.get('walker').features[0].geometry.coordinates,center)<.01);
});
test('continuous camera frames coalesce mesh refreshes without postponing them indefinitely',()=>{
 let id=0,queries=0;
 const timers=new Map();
 const scene=vm.createContext({window:{maplibregl:{}},setTimeout(fn,delay){timers.set(++id,{fn,delay});return id;},clearTimeout(id){timers.delete(id);},
  testMap:{getCenter:()=>({toArray:()=>center}),getZoom:()=>20.8,getBearing:()=>0,querySourceFeatures(){queries++;return [];}}});
 vm.runInContext(fs.readFileSync(path.join(__dirname,'../../main/assets/navigation3d/scene.js'),'utf8'),scene);
 vm.runInContext('map=testMap;loaded=true;for(let i=0;i<60;i++)requestMesh()',scene);
 assert.equal(timers.size,1);assert.equal(id,1);
 let timer=timers.get(1);timers.delete(1);timer.fn();assert.equal(queries,1);
 vm.runInContext('for(let i=0;i<60;i++)requestMesh()',scene);
 timer=[...timers.values()][0];timer.fn();assert.equal(queries,2);
});
function motionHarness(){
 let now=0,id=0;
 const frames=new Map(),sources=new Map(),poses=[];
 const scene=vm.createContext({window:{maplibregl:{}},performance:{now:()=>now},requestAnimationFrame(fn){frames.set(++id,fn);return id;},cancelAnimationFrame(id){frames.delete(id);},
  initial:walkingCamera,testMap:{getBearing:()=>0,getSource(name){return {setData(data){sources.set(name,data);}};},
   calculateCameraOptionsFromTo(eye,height,ground){poses.push({eye,height,ground});return {center:ground,pitch:85,zoom:20.8};},jumpTo(){}}});
 vm.runInContext(fs.readFileSync(path.join(__dirname,'../../main/assets/navigation3d/scene.js'),'utf8'),scene);
 vm.runInContext('map=testMap;loaded=true;state={...initial,bearing:0,route:[initial.walker,offset(initial.walker,0,100)],future:[initial.walker,offset(initial.walker,0,100)]};lastWalker=initial.walker;cameraYaw=0;source("future",line(state.future));',scene);
 return {scene,sources,poses,step(time){now=time;const batch=[...frames];frames.clear();for(const [,fn] of batch)fn(now);},turn(angle){scene.window.navigation3d.face(angle);}};
}
function heading(a,b){return (Math.atan2((b[0]-a[0])*Math.cos(a[1]*Math.PI/180),b[1]-a[1])*180/Math.PI+360)%360;}
function angleBetween(a,b){return Math.abs(((a-b+540)%360)-180);}
test('phone rotation changes yaw in place while world route and measured location remain unchanged',()=>{
 const h=motionHarness(),route=JSON.stringify(h.sources.get('future'));
 h.turn(90);for(let t=16;t<=1000;t+=16)h.step(t);
 assert.ok(h.poses.length>5);
 assert.ok(h.poses.every(p=>separation(p.eye,center)<.001));
 assert.ok(angleBetween(heading(h.poses.at(-1).eye,h.poses.at(-1).ground),90)<.2);
 assert.equal(JSON.stringify(h.sources.get('future')),route);
 assert.ok(separation(h.sources.get('walker').features[0].geometry.coordinates,center)<.001);
 const count=h.poses.length;vm.runInContext('state.follow=false',h.scene);h.turn(180);h.step(1100);
 assert.equal(h.poses.length,count);
 assert.equal(h.sources.get('walker').features[0].properties.bearing,180);
});
test('frequent facing events do not restart or slow the current GPS interpolation',()=>{
 const h=motionHarness();
 vm.runInContext('lastWalker=offset(initial.walker,180,2);movePuck(initial.walker,0)',h.scene);
 for(let t=100;t<=1000;t+=100){h.turn(t/10);h.step(t);}
 assert.ok(separation(h.sources.get('walker').features[0].geometry.coordinates,center)<.001);
 assert.ok(h.poses.every(p=>p.height===1.7));
});
test('yaw smoothing crosses north without a full spin and unavailable facing uses the route fallback',()=>{
 const h=motionHarness();vm.runInContext('cameraYaw=359',h.scene);h.turn(1);
 for(let t=16;t<=800;t+=16)h.step(t);
 assert.ok(h.poses.every(p=>angleBetween(heading(p.eye,p.ground),0)<2));
 h.turn(null);for(let t=816;t<=1600;t+=16)h.step(t);
 assert.ok(angleBetween(heading(h.poses.at(-1).eye,h.poses.at(-1).ground),0)<.2);
});
test('first-person readiness uses real map pixels even when the geographic marker is under the camera',()=>{
 const h=frameHarness();h.scene.testMap.project=()=>{throw Error('Under-camera GPS point cannot prove readiness');};
 vm.runInContext('state={walking:true,follow:true,walker:[0,0]}',h.scene);h.check();assert.equal(h.ready,1);
});
test('touch pauses live following immediately and a queued GPS packet cannot resume it',()=>{
 let browsed=0;
 const scene=vm.createContext({window:{maplibregl:{},NativeScene:{browse(){browsed++;}}},
  document:{getElementById:()=>null},cancelAnimationFrame(){},initial:walkingCamera,
  testMap:{setLayoutProperty(){}}});
 vm.runInContext(fs.readFileSync(path.join(__dirname,'../../main/assets/navigation3d/scene.js'),'utf8'),scene);
 vm.runInContext('map=testMap;loaded=true;state={...initial,recenter:0};beginBrowse();beginBrowse()',scene);
 assert.equal(browsed,1);assert.equal(vm.runInContext('state.follow',scene),false);
 vm.runInContext('loaded=false;window.navigation3d.update({...initial,recenter:0})',scene);
 assert.equal(vm.runInContext('state.follow',scene),false);
 vm.runInContext('window.navigation3d.update({...initial,recenter:1})',scene);
 assert.equal(vm.runInContext('state.follow',scene),true);
});
