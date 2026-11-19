const renderer=window.maplibregl;

const empty = () => ({type:'FeatureCollection',features:[]});
const point = (coordinates,properties={}) => ({type:'Feature',geometry:{type:'Point',coordinates},properties});
const line = coordinates => coordinates.length>1 ? {type:'FeatureCollection',features:[{type:'Feature',geometry:{type:'LineString',coordinates},properties:{}}]} : empty();
const meters = (a,b) => Math.hypot((a[0]-b[0])*111320*Math.cos(a[1]*Math.PI/180),(a[1]-b[1])*111320);
function offset(origin,bearing,distance){
 const r=Math.PI/180,lat=origin[1]*r,lng=origin[0]*r,angle=bearing*r,d=distance/6371008.8;
 const nextLat=Math.asin(Math.sin(lat)*Math.cos(d)+Math.cos(lat)*Math.sin(d)*Math.cos(angle));
 const nextLng=lng+Math.atan2(Math.sin(angle)*Math.sin(d)*Math.cos(lat),Math.cos(d)-Math.sin(lat)*Math.sin(nextLat));
 return [((nextLng/r+540)%360)-180,nextLat/r];
}
const native = (name,...args) => { try { window.NativeScene?.[name](...args); } catch {} };
const tileId = ['get','partId'];
const exterior = ['all',['!=',['get','hide_3d'],true],['!=',['get','hide_3d'],1]];
let map,state,creating=false,loaded=false,generation=0,meshCenter=null,meshZoom=0,meshBearing=0,meshTimer,meshForced=false,meshSignature='';
let bodies=empty(),edges=empty(),cityFeatures=empty(),citySignature='',occluded=new Map(),previewKey='',previewTall=false,lastWalker,lastBearing=0,puckFrame;
let cameraFrame,cameraYaw,cameraClock=0,facingHeading=null,positionKey='',wasFollowing=false,browseRecenter=null;
let viewportHeight=0,viewportWidth=0;
function sizeViewport(next){
 const height=next.viewportHeight||window.innerHeight,width=next.viewportWidth||window.innerWidth;
 if(height>0&&width>0&&(height!==viewportHeight||width!==viewportWidth)){
  viewportHeight=height;viewportWidth=width;
  for(const e of [document.documentElement,document.body,document.getElementById('map')]){e.style.height=height+'px';e.style.width=width+'px';}
  map?.resize();
 }
}
let routeKey='',occlusionTimer,drawReady=false,lastNativeFrame=0,drawFailures=0,startupPhase='waiting',contextAttempts=0;
let sceneRestarts=0,rebuilding=false,frameCheckTimer,healthTimer,paused=false,baseTileReady=false;

function recoverScene(){
 if(rebuilding)return;
 if(sceneRestarts>=1){startupPhase='failed';native('failed');return;}
 rebuilding=true;sceneRestarts++;startupPhase='recovering';loaded=false;drawReady=false;
 native('loading');
 clearTimeout(meshTimer);clearTimeout(occlusionTimer);clearTimeout(frameCheckTimer);clearTimeout(healthTimer);cancelAnimationFrame(puckFrame);
 cancelAnimationFrame(cameraFrame);puckFrame=undefined;cameraFrame=undefined;cameraYaw=undefined;cameraClock=0;positionKey='';wasFollowing=false;
 meshTimer=undefined;occlusionTimer=undefined;meshForced=false;
 const previous=map;map=null;previous?.remove();
 generation++;meshCenter=null;meshSignature='';previewKey='';routeKey='';previewTall=false;
 bodies=empty();edges=empty();cityFeatures=empty();citySignature='';occluded.clear();lastWalker=undefined;drawFailures=0;baseTileReady=false;
 setTimeout(()=>{rebuilding=false;create(state);},150);
}

function bootstrapFrame(candidate){
 return new Promise(resolve=>{
  const timeout=setTimeout(()=>finish(false),3000);
  function finish(ok){clearTimeout(timeout);candidate.off('load',check);resolve(ok);}
  function check(){
   const c=candidate.getCanvas(),gl=c.getContext('webgl2'),pixel=new Uint8Array(4);
   if(!gl||gl.isContextLost()){finish(false);return;}
   gl.readPixels(Math.floor(c.width/2),Math.floor(c.height/2),1,1,gl.RGBA,gl.UNSIGNED_BYTE,pixel);
   finish(pixel[3]===255);native('frame');
  }
  candidate.once('load',check);
 });
}

function source(name,data){ map.getSource(name)?.setData(data); }
function icon(name,kind){
 const canvas=document.createElement('canvas');canvas.width=48;canvas.height=48;const c=canvas.getContext('2d');
 c.lineJoin='round';c.lineCap='round';
 if(kind==='puck'){
  c.fillStyle='rgba(39,229,209,.2)';c.beginPath();c.arc(24,24,23,0,Math.PI*2);c.fill();
  c.beginPath();c.moveTo(24,7);c.lineTo(38,39);c.lineTo(24,32);c.lineTo(10,39);c.closePath();
  c.lineWidth=4;c.strokeStyle='#10243c';c.stroke();c.fillStyle='#e7fffb';c.fill();
 }else{ c.strokeStyle='#eefffc';c.lineWidth=4;c.beginPath();c.moveTo(17,12);c.lineTo(30,24);c.lineTo(17,36);c.stroke(); }
 map.addImage(name,c.getImageData(0,0,48,48),{pixelRatio:2});
}
function addLayers(){
 for(const name of ['details','city-bodies','building-base','route','future','destination','street','walker']) map.addSource(name,{type:'geojson',data:empty(),
  ...(name==='details'?{tolerance:0,maxzoom:20}:{}),...(name==='city-bodies'?{promoteId:'partId'}:{})});
 // Split grouped tile geometry so fading one building cannot fade a whole city block.
 map.addLayer({id:'base-glow',type:'line',source:'building-base',paint:{'line-color':'#4d76d0','line-width':8,'line-blur':6,'line-opacity':.28}});
 map.addLayer({id:'city-solid',type:'fill-extrusion',source:'city-bodies',filter:exterior,paint:{
  'fill-extrusion-height':['get','top'],'fill-extrusion-base':['get','base'],
  'fill-extrusion-color':['interpolate',['linear'],['get','distance'],100,'#28354f',600,'#1e2c43',1500,'#141e31'],
  'fill-extrusion-opacity':.94,'fill-extrusion-vertical-gradient':true}});
 map.addLayer({id:'city-cutaway',type:'fill-extrusion',source:'city-bodies',
  filter:['all',exterior,['in',tileId,['literal',[]]]],paint:{'fill-extrusion-height':['get','top'],'fill-extrusion-base':['get','base'],
  'fill-extrusion-color':'#273d63','fill-extrusion-opacity':.10,'fill-extrusion-vertical-gradient':true}});
 for(const [kind,opacity] of [['halo',.13],['floor',.28],['facade',.72],['corner',.35],['roof',.84]]){
  const accent=['any',['==',['get','accent'],true],['==',['get','selected'],true]];
  const normal=['all',['==',['get','kind'],kind],['!=',['get','cutaway'],true],...(kind==='facade'?[accent]:[])];
  const color=kind==='floor'?'#6385b3':kind==='facade'?'#86afff':kind==='roof'?'#d3e8fb':'#7299d7';
  map.addLayer({id:'detail-'+kind,type:'fill-extrusion',source:'details',filter:normal,paint:{
   'fill-extrusion-base':['get','base'],'fill-extrusion-height':['get','top'],'fill-extrusion-opacity':opacity,
   'fill-extrusion-vertical-gradient':kind!=='roof',
   'fill-extrusion-color':['case',['==',['get','selected'],true],'#ffd28e',
    ['interpolate',['linear'],['get','distance'],30,color,160,'#425c87',280,'#25334c']]}});
 }
 map.addLayer({id:'route-complete',type:'line',source:'route',layout:{'line-cap':'round','line-join':'round'},paint:{'line-color':'#37637a','line-width':4,'line-opacity':.6}});
 for(const [id,width,color,opacity,blur] of [['route-glow',22,'#16dabb',.45,7],['route-ribbon',8,'#22d9bd',1,0],['route-core',2.3,'#effffc',1,0]])
  map.addLayer({id,type:'line',source:'future',layout:{'line-cap':'round','line-join':'round'},paint:{'line-color':color,'line-width':width,'line-opacity':opacity,'line-blur':blur}});
 icon('walker','puck');icon('chevron','chevron');
 map.addLayer({id:'route-chevrons',type:'symbol',source:'future',layout:{'symbol-placement':'line','symbol-spacing':45,'icon-image':'chevron','icon-size':.55,'icon-allow-overlap':true,'icon-ignore-placement':true,'icon-rotation-alignment':'map','icon-pitch-alignment':'map','icon-keep-upright':false}});
 map.addLayer({id:'walker',type:'symbol',source:'walker',layout:{'icon-image':'walker','icon-rotate':['get','bearing'],'icon-rotation-alignment':'map','icon-pitch-alignment':'viewport','icon-allow-overlap':true,'icon-ignore-placement':true}});
 map.addLayer({id:'street-halo',type:'circle',source:'street',paint:{'circle-color':'#26e2ce','circle-radius':10,'circle-opacity':.3,'circle-blur':.5,'circle-pitch-scale':'viewport'}});
 map.addLayer({id:'street-dot',type:'circle',source:'street',paint:{'circle-color':'#e5fffc','circle-radius':3.5,'circle-pitch-scale':'viewport'}});
 map.addLayer({id:'destination-anchor',type:'circle',source:'destination',paint:{'circle-color':'#ffd28e','circle-radius':4.5,'circle-stroke-width':2,'circle-stroke-color':'#142039','circle-pitch-scale':'viewport'}});
 map.addLayer({id:'destination-number',type:'symbol',source:'destination',layout:{'text-field':['get','label'],'text-font':['Noto Sans Regular'],'text-size':19,'text-anchor':'bottom','text-offset':[0,-.7],'text-allow-overlap':true},paint:{'text-color':'#ffd28e','text-halo-color':'#121d30','text-halo-width':3}});
}
async function create(initial){
 state=initial;
 try{
  startupPhase='style';
  const style=await fetch('../navigation-3d.json').then(r=>r.json());
  style.name='Walkline walking city';style.light={anchor:'viewport',color:'#b4cfff',intensity:.4,position:[1.5,220,38]};
  style.sky={'sky-color':'#10182d','horizon-color':'#465c90','sky-horizon-blend':.7,'horizon-fog-blend':.5,'fog-color':'#25334e','atmosphere-blend':0};
  for(const layer of style.layers){
   if(layer.id==='background')layer.paint['background-color']='#141d31';
   if(layer.id==='road-casing'){layer.paint['line-color']='#8a98b2';layer.paint['line-opacity']=.8;layer.paint['line-width']=['interpolate',['linear'],['zoom'],12,1,16,5,18,20,20,68,22,272];}
   if(layer.id==='roads'){layer.paint['line-color']='#27364d';layer.paint['line-width']=['interpolate',['linear'],['zoom'],12,.5,16,3,18,16,20,59,22,236];}
   if(layer.id==='paths'){layer.paint['line-color']='#738ba6';layer.paint['line-opacity']=.45;layer.paint['line-width']=['interpolate',['linear'],['zoom'],16,1,20,3,22,12];}
   if(layer.id==='street-labels'){layer.paint['text-opacity']=.65;layer.layout['text-size']=10;}
  }
  await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
  // Render a simple local frame first. Creating a tiled high-pitch scene before the
  // first Android visual frame can leave WebView's drawing buffer transparent.
  const bootstrap={version:8,sources:{},layers:[{id:'bootstrap',type:'background',paint:{'background-color':'#141d31'}}]};
  let usable=false;
  for(contextAttempts=1;contextAttempts<=2;contextAttempts++){
   map=new renderer.Map({container:'map',style:bootstrap,center:state.focus,zoom:18.6,pitch:70,bearing:state.bearing,
    minPitch:55,maxPitch:88,maxZoom:23,pixelRatio:Math.min(window.devicePixelRatio,1.75),maxTileCacheSize:48,renderWorldCopies:false,attributionControl:false,canvasContextAttributes:{antialias:false,preserveDrawingBuffer:true},fadeDuration:250});
   startupPhase='bootstrap';
   usable=await bootstrapFrame(map);
   if(usable)break;
   map.remove();map=null;
   await new Promise(resolve=>setTimeout(resolve,150));
  }
  if(!usable){startupPhase='failed';native('failed');return;}
  map.setVerticalFieldOfView(60);
  const owner=map;
  // Stop the camera before MapLibre handles a touch. Waiting for movestart can
  // lose a gesture to the next compass frame calling jumpTo.
  for(const event of ['pointerdown','touchstart','wheel'])
   owner.getCanvas().addEventListener(event,beginBrowse,{capture:true,passive:true});
  map.addControl(new renderer.AttributionControl({compact:true,customAttribution:'<a href="https://www.openstreetmap.org/fixthemap">Fix map</a>'}),'bottom-left');
  map.once('style.load',()=>{addLayers();loaded=true;startupPhase='scene';applyState();requestMesh(true);armFrameCheck();map.triggerRepaint();});
  map.on('render',verifyFrame);
  map.on('moveend',()=>{requestMesh();scheduleOcclusion();});
  map.on('sourcedata',e=>{if(e.sourceId==='openmaptiles'){
   if(e.sourceDataType==='content')baseTileReady=true;
   if(e.isSourceLoaded)requestMesh(true);
  }});
  map.on('movestart',e=>{if(e.originalEvent)beginBrowse();});
  map.on('click',e=>{if(state.picking)native('pick',e.lngLat.lng,e.lngLat.lat);});
  map.on('error',e=>{if(!loaded)console.error('3D map resource failed: '+String(e.error?.message||'unknown').slice(0,130));});
  map.on('webglcontextlost',()=>{if(map===owner)recoverScene();});
  map.setStyle(style);
  setTimeout(()=>{if(!loaded)native('failed');},20000);
 }catch(error){console.error('3D scene startup: '+error.message);native('failed');}
}
function armFrameCheck(){
 clearTimeout(healthTimer);
 if(!paused&&loaded)healthTimer=setTimeout(()=>{verifyFrame();armFrameCheck();},1000);
}
function verifyFrame(){
 if(paused||!map)return;
 const now=performance.now();
 if(now-lastNativeFrame<(drawReady?1000:150))return;
 lastNativeFrame=now;native('frame');
 if(!loaded)return;
 const canvas=map.getCanvas(),ratio=map.getPixelRatio();
 const gl=canvas.getContext('webgl2');
 if(!gl||gl.isContextLost()){recoverScene();return;}
 if(gl.getParameter(gl.FRAMEBUFFER_BINDING)!==null)return;
 const pixel=new Uint8Array(4);
 // A pending horizon tile or an off-screen marker must not disable blank-frame
 // recovery. Check the retained drawing buffer even when no render event arrives.
 gl.readPixels(Math.floor(canvas.width/2),Math.floor(canvas.height/2),1,1,gl.RGBA,gl.UNSIGNED_BYTE,pixel);
 if(pixel[3]===0){
  if(++drawFailures>=3){recoverScene();return;}
  clearTimeout(frameCheckTimer);frameCheckTimer=setTimeout(()=>map?.triggerRepaint(),200);return;
 }
 drawFailures=0;
 if(!baseTileReady)return;
 if(state.walking&&state.follow&&state.walker){
  // The actual GPS point is underneath an eye-position camera. Verify map pixels,
  // not the separate viewpoint HUD or a marker that must be behind the near plane.
  if(!drawReady&&visibleScenePixels(gl,canvas)){drawReady=true;native('ready');}
  return;
 }
 const target=state.walking&&!state.arrived&&lastWalker?lastWalker:state.destination;
 const p=map.project(target);
 if(p.x<4||p.y<4||p.x>canvas.clientWidth-4||p.y>canvas.clientHeight-4)return;
 gl.readPixels(Math.floor(p.x*ratio),canvas.height-1-Math.floor(p.y*ratio),1,1,gl.RGBA,gl.UNSIGNED_BYTE,pixel);
 // A rendered navigation marker proves useful map output, rather than only a cleared background.
 if(pixel[3]>100&&pixel[0]>130&&pixel[1]>100){
  drawFailures=0;if(!drawReady){drawReady=true;native('ready');}
 }
}
function visibleScenePixels(gl,canvas){
 const colors=new Set(),pixel=new Uint8Array(4);let visible=0;
 for(const x of [.25,.5,.75])for(const y of [.25,.5,.75]){
  gl.readPixels(Math.floor(canvas.width*x),Math.floor(canvas.height*y),1,1,gl.RGBA,gl.UNSIGNED_BYTE,pixel);
  if(pixel[3]>100){visible++;colors.add(Array.from(pixel).join(','));}
 }
 return visible>=3&&colors.size>=2;
}
function renderWalker(){
 source('walker',lastWalker?{type:'FeatureCollection',features:[point(lastWalker,{bearing:facingHeading??lastBearing})]}:empty());
}
function cameraTarget(){return facingHeading??state.bearing;}
function aimCamera(now){
 if(cameraYaw===undefined)cameraYaw=map.getBearing();
 const target=cameraTarget(),angle=((target-cameraYaw+540)%360)-180;
 const elapsed=Math.max(0,Math.min(100,now-cameraClock));cameraClock=now;
 cameraYaw=Math.abs(angle)<.1?target:(cameraYaw+angle*(1-Math.exp(-elapsed/90))+360)%360;
 if(state.walking&&state.follow&&lastWalker){
  const next={...state,walker:lastWalker,bearing:cameraYaw};
  if(eyeView(next))map.jumpTo(cameraOptions(next));
 }
 return Math.abs(((target-cameraYaw+540)%360)-180)>.1;
}
function queueCamera(){
 if(!loaded||paused||puckFrame||cameraFrame||!state.walking||!state.follow||!lastWalker)return;
 cameraFrame=requestAnimationFrame(now=>{cameraFrame=undefined;if(aimCamera(now))queueCamera();});
}
function updateViewpoint(){
 const active=!!(state.walking&&state.follow&&state.walker);
 map.setLayoutProperty('walker','visibility',active?'none':'visible');
 const marker=document.getElementById('viewpoint');
 if(marker){
  marker.style.display=active?'block':'none';
  const height=state.viewportHeight||window.innerHeight;
  marker.style.top=Math.min(height-80,state.topPadding+(height-state.topPadding)*.68)+'px';
 }
}
function beginBrowse(){
 if(!loaded||!state.walking||!state.follow)return;
 browseRecenter=state.recenter;state.follow=false;
 cancelAnimationFrame(cameraFrame);cameraFrame=undefined;
 updateViewpoint();native('browse');
}
function movePuck(position,bearing){
 if(!position){lastWalker=undefined;renderWalker();return;}
 cancelAnimationFrame(puckFrame);
 cancelAnimationFrame(cameraFrame);cameraFrame=undefined;
 const start=lastWalker||position,initial=lastBearing,angle=((bearing-initial+540)%360)-180,time=performance.now();
 const distance=meters(start,position),moving=distance>.02||Math.abs(angle)>.1;
 const duration=state.walking&&distance<30&&moving?(state.arrived?1400:1000):0;
 function frame(now){const t=duration?Math.min(1,(now-time)/duration):1;
  puckFrame=undefined;
  lastWalker=[start[0]+(position[0]-start[0])*t,start[1]+(position[1]-start[1])*t];lastBearing=(initial+angle*t+360)%360;
  renderWalker();aimCamera(now);
  if(t<1)puckFrame=requestAnimationFrame(frame);else queueCamera();
 }
 puckFrame=requestAnimationFrame(frame);
}
function eyeView(next){
 const origin=next.walker;
 if(!next.walking||next.picking||!origin)return null;
 const turn=next.guiding&&Number.isFinite(next.cameraTurn)?Math.max(0,Math.min(1,next.cameraTurn)):0;
 const height=1.7,pitch=85-.5*turn,eye=origin;
 // Rotate at the actual measured position. A separate viewpoint HUD replaces
 // the ground marker under the camera; no orbit or route-centre snap is applied.
 return {eye,ground:offset(eye,next.bearing,height/Math.tan((90-pitch)*Math.PI/180)),height,pitch};
}
function cameraOptions(next,camera=map){
 const view=eyeView(next);
 if(view)return {...camera.calculateCameraOptionsFromTo(view.eye,view.height,view.ground,0),
  roll:0,padding:{left:0,right:0,top:0,bottom:0},duration:next.arrived?1400:850};
 return {center:next.focus,bearing:next.bearing,pitch:next.picking?60:70,zoom:18.6,elevation:0,roll:0,
  padding:{left:8,right:8,top:next.topPadding+8,bottom:20},duration:850};
}
function applyState(){
 if(!loaded)return;
 const key=JSON.stringify(state.route);
 if(key!==routeKey){source('route',line(state.route));routeKey=key;}
 source('future',line(state.arrived?[]:state.future));
 map.setPaintProperty('route-ribbon','line-opacity',state.guiding?1:.4);
 map.setLayoutProperty('route-chevrons','visibility',state.guiding&&!state.arrived?'visible':'none');
 map.setLayoutProperty('walker','icon-size',state.walking?.55:1);
 map.setLayoutProperty('street-labels','visibility',state.walking?'none':'visible');
 source('destination',{type:'FeatureCollection',features:[point(state.destination,{label:state.label})]});
 source('street',state.street?{type:'FeatureCollection',features:[point(state.street)]}:empty());
 if(state.follow&&!wasFollowing){cameraYaw=map.getBearing();cameraClock=performance.now();}
 wasFollowing=state.follow;
 const position=JSON.stringify([state.walker,state.walkerBearing]);
 if(position!==positionKey){positionKey=position;movePuck(state.walker,state.walkerBearing);}else renderWalker();
 updateViewpoint();queueCamera();
 const frameKey=JSON.stringify([state.destination,state.street,state.recenter,state.walking]);
 if((state.walking&&state.follow&&!eyeView(state))||(!state.walking&&previewKey!==frameKey)){
  if(!state.walking){previewKey=frameKey;previewTall=false;}
  map.easeTo(cameraOptions(state));
 }
 requestMesh();scheduleOcclusion();
}
function buildingParts(features,center,radius,limit){
 // Vector tiles can group thousands of separate buildings in one MultiPolygon.
 // Choose nearby parts before limiting the batch, not the group's first polygon.
 return features.flatMap(feature=>{
  const g=feature.geometry;
  if(g?.type==='Polygon')return [feature];
  if(g?.type==='MultiPolygon'&&Array.isArray(g.coordinates))return g.coordinates.map(rings=>({type:'Feature',id:feature.id,
   properties:feature.properties,geometry:{type:'Polygon',coordinates:rings}}));
  return [];
 }).map(feature=>{
  const p=feature.geometry.coordinates?.[0]?.[0];
  return {feature,distance:p&&p.length>=2&&p.every(Number.isFinite)?meters(center,p):Infinity};
 }).filter(item=>item.distance<radius).sort((a,b)=>a.distance-b.distance).slice(0,limit).map(item=>item.feature);
}
function nearbyBuildings(features,center){return buildingParts(features,center,380,300);}
function cityBuildings(features,center){
 const seen=new Set(),result=[];
 for(const feature of buildingParts(features,center,1500,3000)){
  const p=feature.properties||{},rings=feature.geometry.coordinates;
  if(['true','1'].includes(String(p.hide_3d)))continue;
  const top=Number(p.render_height??p.height??6),base=Number(p.render_min_height??0);
  if(!Number.isFinite(top)||top<=0||!Number.isFinite(base))continue;
  const key=[String(feature.id??''),JSON.stringify(rings[0]),top,base].join(':');
  if(seen.has(key))continue;seen.add(key);
  const first=rings[0][0];
  // A small stable hash keeps per-part IDs out of the large geometry payload.
  let hash=2166136261;for(let i=0;i<key.length;i++)hash=Math.imul(hash^key.charCodeAt(i),16777619);
  const id=String(feature.id??'')+':'+(hash>>>0).toString(16);
  // MapLibre query results also carry the tile parser/PBF buffer. Never copy those
  // internals into a GeoJSON source or a worker message.
  result.push({type:'Feature',geometry:feature.geometry,id,properties:{...p,partId:id,sourceId:String(feature.id??''),top:Math.min(700,Math.max(3,top)),
   base:Math.min(Math.max(0,base),Math.min(700,Math.max(3,top))),distance:meters(center,first)}});
  if(result.length>=2400)break;
 }
 return result;
}
function requestMesh(force=false){
 if(!loaded||paused)return;
 meshForced ||= force;
 if(meshTimer)return;
 meshTimer=setTimeout(()=>{
  meshTimer=undefined;
  if(!loaded||paused||!map)return;
  const forced=meshForced;meshForced=false;
  const c=map.getCenter().toArray(),zoom=map.getZoom(),bearing=map.getBearing();
  const stable=meshCenter&&meters(c,meshCenter)<14&&Math.abs(zoom-meshZoom)<.4&&Math.abs(bearing-meshBearing)<20;
  if(!forced&&stable)return;
  const parts=cityBuildings(map.querySourceFeatures('openmaptiles',{sourceLayer:'building'}),c);
  const nextCity=parts.map(f=>f.id).join('|');
  if(nextCity!==citySignature){citySignature=nextCity;cityFeatures={type:'FeatureCollection',features:parts};source('city-bodies',cityFeatures);scheduleOcclusion();}
  const features=parts.filter(f=>f.properties.distance<380).slice(0,300).map(f=>({type:'Feature',geometry:f.geometry,properties:f.properties,id:f.id}));
  if(!features.length)return;
  const signature=features.map(f=>String(f.id)+JSON.stringify(f.geometry.coordinates[0])).join('|');
  if(stable&&signature===meshSignature)return;
  meshSignature=signature;
  meshCenter=c;meshZoom=zoom;meshBearing=bearing;
  const p=map.project(c),q=map.project([c[0]+1/(111320*Math.cos(c[1]*Math.PI/180)),c[1]]);
  const ppm=Math.max(.05,Math.hypot(p.x-q.x,p.y-q.y));
  native('mesh',JSON.stringify({generation:++generation,center:c,destination:state.destination,stroke:.55/ppm,features:{type:'FeatureCollection',features}}));

 },250);
}
function scheduleOcclusion(){
 if(!loaded||paused||occlusionTimer)return;occlusionTimer=setTimeout(updateOcclusion,250);
}
function insideRing(point,ring){
 let inside=false;
 for(let i=0,j=ring.length-1;i<ring.length;j=i++){
  const a=ring[i],b=ring[j];
  if((a[1]>point[1])!==(b[1]>point[1])&&point[0]<(b[0]-a[0])*(point[1]-a[1])/(b[1]-a[1])+a[0])inside=!inside;
 }
 return inside;
}
function buildingObstructs(camera,altitude,target,feature){
 const rings=feature.geometry.coordinates,top=feature.properties.top,base=feature.properties.base;
 if(!Number.isFinite(altitude)||altitude<=0||top<=base)return false;
 const start=Math.max(0,1-top/altitude),end=Math.min(1,1-base/altitude);
 if(start>=end)return false;
 const dx=target[0]-camera[0],dy=target[1]-camera[1],crossings=[start,end];
 const at=t=>[camera[0]+dx*t,camera[1]+dy*t];
 const inside=p=>insideRing(p,rings[0])&&!rings.slice(1).some(hole=>insideRing(p,hole));
 for(const ring of rings)for(let i=1;i<ring.length;i++){
  const a=ring[i-1],b=ring[i],ex=b[0]-a[0],ey=b[1]-a[1],denominator=dx*ey-dy*ex;
  if(Math.abs(denominator)<1e-18)continue;
  const ax=a[0]-camera[0],ay=a[1]-camera[1];
  const t=(ax*ey-ay*ex)/denominator,u=(ax*dy-ay*dx)/denominator;
  if(t>start&&t<end&&u>=0&&u<=1)crossings.push(t);
 }
 crossings.sort((a,b)=>a-b);
 return crossings.slice(1).some((next,i)=>next-crossings[i]>1e-8&&inside(at((next+crossings[i])/2)));
}
function updateOcclusion(){
 occlusionTimer=undefined;
 if(!loaded||paused)return;
 const targets=[state.walking&&!state.arrived&&state.walker?state.walker:state.street||state.destination];
 if(state.guiding&&state.future.length>1)targets.push(state.future[1]);
 const now=performance.now();
 // The pinned renderer exposes its camera pose. Test the actual ray against each
 // footprint/height span; high-pitch rendered-feature queries omit these bodies.
 const camera=map.transform.getCameraLngLat?.(),altitude=map.transform.getCameraAltitude?.();
 if(camera&&Number.isFinite(altitude))for(const target of targets){
  const rayStart=[camera.lng,camera.lat];
  const reach=meters(rayStart,target)+100;
  for(const feature of cityFeatures.features){
   if(feature.properties.distance>reach+100)continue;
   if(buildingObstructs(rayStart,altitude,target,feature))occluded.set(String(feature.id),now);
  }
 }
 for(const [id,last]of occluded)if(now-last>900)occluded.delete(id);
 const ids=[...occluded.keys()];
 map.setFilter('city-solid',['all',exterior,['!', ['in',tileId,['literal',ids]]]]);
 map.setFilter('city-cutaway',['all',exterior,['in',tileId,['literal',ids]]]);
 let changed=false;
 for(const feature of edges.features){const fade=occluded.has(feature.properties.tileId);if(feature.properties.cutaway!==fade){feature.properties.cutaway=fade;changed=true;}}
 if(changed)source('details',edges);
 if(occluded.size)occlusionTimer=setTimeout(updateOcclusion,500);
}
window.navigation3d={
 inspect(){return {loaded,drawReady,startupPhase,contextAttempts,sceneRestarts,baseTileReady,pitch:map?.getPitch(),bearing:map?.getBearing(),facingHeading,zoom:map?.getZoom(),cameraHeightM:map?.transform.getCameraAltitude(),fov:map?.getVerticalFieldOfView(),cameraTurn:state?.cameraTurn,cityBuildings:cityFeatures.features.length,buildings:bodies.features.length,ids:bodies.features.filter(f=>f.properties.tileId).length,edges:edges.features.length,occluded:occluded.size};},
 face(heading){
  facingHeading=Number.isFinite(heading)?((heading%360)+360)%360:null;
  if(loaded&&!paused){renderWalker();queueCamera();}
 },
 update(next){
  // A GPS packet already queued by Android must not undo a fresh touch gesture.
  // The explicit Follow walk action increments recenter and releases this latch.
  if(browseRecenter!==null){
   if(!next.walking||next.recenter!==browseRecenter)browseRecenter=null;
   else next={...next,follow:false};
  }
  state=next;sizeViewport(next);if(!map){if(!creating){creating=true;create(next);}return;}applyState();
 },
 mesh(result){
  if(!loaded||result.generation!==generation)return;
  edges=result.edges;bodies=result.bodies;source('details',edges);source('building-base',bodies);
  if(!state.walking&&!state.picking&&!previewTall){
   const h=Math.max(0,...result.selected.features.map(f=>f.properties.top),
    ...result.bodies.features.filter(f=>f.properties.distance<180).map(f=>f.properties.top));
   if(h>60){previewTall=true;map.easeTo({zoom:h>250?16.6:h>120?17.3:17.8,pitch:70,duration:800});}
  }
  scheduleOcclusion();
 },
 pause(){paused=true;map?.stop();cancelAnimationFrame(puckFrame);cancelAnimationFrame(cameraFrame);puckFrame=undefined;cameraFrame=undefined;positionKey='';facingHeading=null;clearTimeout(occlusionTimer);clearTimeout(meshTimer);clearTimeout(frameCheckTimer);clearTimeout(healthTimer);occlusionTimer=undefined;meshTimer=undefined;meshForced=false;},
 resume(){paused=false;map?.resize();if(loaded)applyState();armFrameCheck();map?.triggerRepaint();}
};
