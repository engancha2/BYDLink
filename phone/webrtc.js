/* BYDLink · cámara en vivo
 * En modo DEMO usa un stream sintético (canvas).
 * En modo FIREBASE negocia WebRTC con la app del coche a través de /signaling.
 * El media (vídeo) viaja directo coche -> móvil; Firebase sólo pasa SDP/ICE.
 */
(function () {
  'use strict';
  const ICE = { iceServers: [
    { urls: 'stun:stun.l.google.com:19302' },
    { urls: 'stun:stun1.l.google.com:19302' }
    // TURN opcional: añadir aquí { urls:'turn:...', username, credential } si P2P falla.
  ]};

  let pc = null, session = null, curStream = null, curCam = null;

  async function start(camera, videoEl, onStatus) {
    stop();
    curCam = camera;
    onStatus && onStatus('Conectando…');

    // demo y cloud (provisional) usan vídeo sintético; firebase usa WebRTC real.
    if (Backend.getDemoCameraStream) {
      curStream = Backend.getDemoCameraStream(camera);
      videoEl.srcObject = curStream;
      videoEl.style.display = 'block';
      onStatus && onStatus(Backend.mode === 'cloud' ? '● EN VIVO (provisional)' : '● EN VIVO (demo)');
      return;
    }

    // --- Firebase / WebRTC real ---
    session = 'live-' + Date.now();
    const sig = Backend.signalRef(session);
    pc = new RTCPeerConnection(ICE);
    pc.addTransceiver('video', { direction: 'recvonly' });
    pc.addTransceiver('audio', { direction: 'recvonly' });

    pc.ontrack = (e) => {
      videoEl.srcObject = e.streams[0];
      videoEl.style.display = 'block';
      onStatus && onStatus('● EN VIVO');
    };
    pc.onicecandidate = (e) => { if (e.candidate) sig.child('ice/phone').push(e.candidate.toJSON()); };
    pc.onconnectionstatechange = () => {
      if (pc && (pc.connectionState === 'failed' || pc.connectionState === 'disconnected'))
        onStatus && onStatus('Conexión perdida');
    };

    // el coche publicará su answer y sus candidatos
    sig.child('answer').on('value', async s => {
      const ans = s.val();
      if (ans && pc && !pc.currentRemoteDescription) {
        await pc.setRemoteDescription(new RTCSessionDescription(ans));
      }
    });
    sig.child('ice/car').on('child_added', s => {
      const c = s.val(); if (c && pc) pc.addIceCandidate(new RTCIceCandidate(c)).catch(() => {});
    });

    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);
    await sig.child('offer').set({ type: offer.type, sdp: offer.sdp });
    // decirle al coche qué cámara y que empiece a emitir en esta sesión
    Backend.sendCommand('startLiveCam', { camera, session });
  }

  function switchCam(camera, videoEl, onStatus) {
    curCam = camera;
    if (Backend.getDemoCameraStream) return start(camera, videoEl, onStatus);
    Backend.sendCommand('switchLiveCam', { camera, session });
    onStatus && onStatus('● EN VIVO · ' + camera);
  }

  function stop() {
    if (!Backend.getDemoCameraStream && session) {
      try { Backend.sendCommand('stopLiveCam', { session }); } catch (e) {}
      try { Backend.signalRef(session).off(); Backend.signalRef(session).remove(); } catch (e) {}
    }
    if (curStream && curStream.__stop) curStream.__stop();
    if (curStream) curStream.getTracks().forEach(t => t.stop());
    if (pc) { try { pc.close(); } catch (e) {} }
    pc = null; session = null; curStream = null; curCam = null;
  }

  window.LiveCam = { start, switchCam, stop, get camera() { return curCam; } };
})();
