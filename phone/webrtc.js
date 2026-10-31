/* BYDLink · cámara en vivo
 * En demo usa un vídeo sintético (canvas). Con Firebase negocia WebRTC con la app del
 * coche a través de /signaling: el vídeo viaja directo coche → móvil; Firebase solo
 * transporta SDP/ICE.
 * onState(state, msg): 'connecting' | 'error' (msg) | 'lost'. El paso a "en directo" lo
 * detecta app.js con el evento 'playing' del <video>.
 */
(function () {
  'use strict';
  const ICE = { iceServers: [
    { urls: 'stun:stun.l.google.com:19302' },
    { urls: 'stun:stun1.l.google.com:19302' }
    // TURN opcional: añadir aquí { urls:'turn:...', username, credential } si P2P falla.
  ]};

  let pc = null, session = null, curStream = null, curCam = null, sig = null;

  async function start(camera, videoEl, onState) {
    stop();
    curCam = camera;
    onState('connecting');

    if (Backend.getDemoCameraStream) {
      curStream = Backend.getDemoCameraStream(camera);
      videoEl.srcObject = curStream;
      videoEl.play().catch(() => {});
      return;
    }

    const mySession = session = 'live-' + Date.now();
    sig = Backend.signalRef(mySession);
    pc = new RTCPeerConnection(ICE);
    pc.addTransceiver('video', { direction: 'recvonly' });
    pc.addTransceiver('audio', { direction: 'recvonly' });

    pc.ontrack = e => {
      videoEl.srcObject = (e.streams && e.streams[0]) || new MediaStream([e.track]);
      videoEl.play().catch(() => {});
    };
    pc.onicecandidate = e => { if (e.candidate && sig) sig.child('ice/phone').push(e.candidate.toJSON()); };
    pc.onconnectionstatechange = () => {
      if (!pc || session !== mySession) return;
      if (pc.connectionState === 'failed') onState('error', 'No se pudo establecer la conexión de vídeo con el coche.');
      else if (pc.connectionState === 'disconnected') onState('lost');
    };

    sig.child('answer').on('value', s => {
      const ans = s.val();
      if (ans && pc && !pc.currentRemoteDescription)
        pc.setRemoteDescription(new RTCSessionDescription(ans)).catch(() => onState('error', 'Respuesta de vídeo no válida.'));
    });
    sig.child('ice/car').on('child_added', s => {
      const c = s.val(); if (c && pc) pc.addIceCandidate(new RTCIceCandidate(c)).catch(() => {});
    });

    try {
      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);
      await sig.child('offer').set({ type: offer.type, sdp: offer.sdp });
    } catch (e) {
      onState('error', 'No se pudo preparar el vídeo en este navegador.');
      return;
    }
    const res = await Backend.sendCommand('startLiveCam', { camera, session: mySession });
    if (session !== mySession) return;
    if (!res || !res.ok)
      onState('error', res && res.error === 'timeout'
        ? 'El coche no responde. Puede estar dormido o sin cobertura.'
        : 'El coche no pudo abrir la cámara.');
  }

  function switchCam(camera, videoEl, onState) {
    if (Backend.getDemoCameraStream || !session) return start(camera, videoEl, onState);
    curCam = camera;
    Backend.sendCommand('switchLiveCam', { camera, session });
  }

  function stop() {
    if (!Backend.getDemoCameraStream && session) {
      try { Backend.sendCommand('stopLiveCam', { session }); } catch (e) {}
      try { sig.child('answer').off(); sig.child('ice/car').off(); sig.remove(); } catch (e) {}
    }
    if (curStream && curStream.__stop) curStream.__stop();
    if (curStream) curStream.getTracks().forEach(t => t.stop());
    if (pc) { try { pc.close(); } catch (e) {} }
    pc = null; session = null; curStream = null; curCam = null; sig = null;
  }

  window.LiveCam = { start, switchCam, stop, get camera() { return curCam; } };
})();
