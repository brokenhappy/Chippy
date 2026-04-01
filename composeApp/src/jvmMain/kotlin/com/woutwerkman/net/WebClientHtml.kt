package com.woutwerkman.net

/**
 * Embedded HTML/JS/CSS for the browser-based game client.
 * Served by the Ktor WebSocket server on GET /.
 */
internal val WEB_CLIENT_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
<title>Chippy</title>
<style>
* { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #121212; color: #e0e0e0; min-height: 100dvh; display: flex; flex-direction: column; }
.container { max-width: 480px; margin: 0 auto; padding: 16px; width: 100%; flex: 1; display: flex; flex-direction: column; }
h1 { color: #bb86fc; font-size: 28px; }
h2 { color: #bb86fc; font-size: 20px; margin-bottom: 12px; }
.subtitle { color: #aaa; font-size: 14px; }
.card { background: #1e1e1e; border-radius: 12px; padding: 16px; margin-bottom: 12px; }
.card-muted { background: #2a2a2a; border-radius: 12px; padding: 24px; text-align: center; color: #aaa; }
button { border: none; border-radius: 12px; padding: 14px 24px; font-size: 16px; font-weight: 600; cursor: pointer; width: 100%; transition: transform 0.1s, opacity 0.2s; }
button:active { transform: scale(0.97); }
button:disabled { opacity: 0.5; cursor: default; }
.btn-primary { background: #bb86fc; color: #121212; }
.btn-green { background: #4CAF50; color: white; }
.btn-danger { background: transparent; color: #cf6679; border: 1px solid #cf6679; padding: 8px 16px; width: auto; }
.header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px; }
.player-row { display: flex; align-items: center; gap: 12px; padding: 12px; background: #1e1e1e; border-radius: 12px; margin-bottom: 8px; }
.avatar { width: 40px; height: 40px; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-weight: bold; font-size: 18px; flex-shrink: 0; }
.avatar-ready { background: #4CAF50; color: white; }
.avatar-waiting { background: #333; color: #bb86fc; }
.badge { font-size: 12px; color: #bb86fc; }
.status { font-size: 12px; }
.status-ready { color: #4CAF50; }
.status-waiting { color: #aaa; }
.game-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; flex: 1; }
.game-btn { aspect-ratio: 1; border-radius: 16px; display: flex; flex-direction: column; align-items: center; justify-content: center; color: white; font-size: 36px; font-weight: bold; border: none; cursor: pointer; transition: transform 0.1s; }
.game-btn:active { transform: scale(0.95); }
.game-btn .name { font-size: 14px; font-weight: 500; opacity: 0.9; }
.game-btn .you-badge { font-size: 10px; font-weight: bold; background: rgba(255,255,255,0.3); padding: 2px 8px; border-radius: 4px; margin-top: 4px; }
.instructions { background: #1e1e1e; border-radius: 12px; padding: 12px; text-align: center; color: #aaa; font-size: 14px; margin-bottom: 16px; }
.countdown-overlay { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0,0,0,0.8); display: flex; flex-direction: column; align-items: center; justify-content: center; z-index: 100; }
.countdown-overlay .label { font-size: 24px; color: white; margin-bottom: 16px; }
.countdown-overlay .number { font-size: 96px; font-weight: bold; color: white; }
.countdown-overlay .win-label { font-size: 28px; font-weight: bold; color: #4CAF50; margin-bottom: 16px; }
.vote-row { display: flex; gap: 16px; margin-bottom: 24px; }
.vote-card { flex: 1; background: #1e1e1e; border-radius: 12px; padding: 16px; text-align: center; }
.vote-card.selected { background: #bb86fc; color: #121212; }
.vote-card .emoji { font-size: 32px; margin-bottom: 8px; }
.vote-card .label { font-size: 16px; font-weight: 600; margin-bottom: 8px; }
.vote-card .count { font-size: 12px; opacity: 0.7; }
.vote-card button { margin-top: 12px; }
.progress-bar { height: 8px; background: #333; border-radius: 4px; overflow: hidden; margin: 12px 0; }
.progress-fill { height: 100%; background: #bb86fc; border-radius: 4px; transition: width 0.3s; }
.spacer { flex: 1; }
.connecting { display: flex; align-items: center; justify-content: center; min-height: 100dvh; color: #aaa; font-size: 18px; }
.name-input { display: flex; flex-direction: column; align-items: center; justify-content: center; min-height: 100dvh; gap: 16px; }
.name-input input { background: #1e1e1e; border: 2px solid #bb86fc; border-radius: 12px; padding: 14px; font-size: 18px; color: white; text-align: center; width: 100%; max-width: 300px; outline: none; }
</style>
</head>
<body>
<div class="container" id="app"></div>
<script>
const app = document.getElementById('app');
let ws;
let state = null;
let localId = null;
let hostId = null;
let peers = [];
let playerName = null;
let currentScreen = 'connecting';

function connect() {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  ws = new WebSocket(proto + '//' + location.host + '/ws');
  ws.onopen = () => { if (playerName) render(); };
  ws.onmessage = (e) => {
    const msg = JSON.parse(e.data);
    if (msg.type === 'com.woutwerkman.net.WsMessage.Identity') {
      localId = msg.localId;
      hostId = msg.hostId;
      peers = msg.peers || [];
      if (!playerName) { currentScreen = 'name'; render(); }
    } else if (msg.type === 'com.woutwerkman.net.WsMessage.StateUpdate') {
      state = msg.state;
      updateScreen();
      render();
    }
  };
  ws.onclose = () => { currentScreen = 'connecting'; render(); setTimeout(connect, 2000); };
}

function send(event) {
  if (ws && ws.readyState === 1) {
    ws.send(JSON.stringify({ type: 'com.woutwerkman.net.WsMessage.EventSubmission', event }));
  }
}

function getMyLobby() {
  if (!state || !localId) return null;
  const lobbies = state.lobbies || {};
  for (const [id, lobby] of Object.entries(lobbies)) {
    if (lobby.players && localId in lobby.players) return lobby;
  }
  return null;
}

function updateScreen() {
  const lobby = getMyLobby();
  if (!lobby) { currentScreen = 'home'; return; }
  const phase = lobby.gamePhase;
  if (phase === 'PLAYING' || phase === 'COUNTDOWN' || phase === 'WIN_COUNTDOWN') currentScreen = 'game';
  else if (phase === 'VOTING') currentScreen = 'voting';
  else currentScreen = 'lobby';
}

function setName(name) {
  playerName = name;
  send({ type: 'com.woutwerkman.net.PeerEvent.JoinedLobby', lobbyId: localId, playerId: localId });
  // Also inform host of name by submitting a name-bearing Joined event won't work since
  // Joined is a system event. The host will need to handle name. For MVP, use "Web Player".
  currentScreen = 'home';
  render();
}

function render() {
  if (currentScreen === 'connecting') { app.innerHTML = '<div class="connecting">Connecting...</div>'; return; }
  if (currentScreen === 'name') { renderNameInput(); return; }
  if (currentScreen === 'home') renderHome();
  else if (currentScreen === 'lobby') renderLobby();
  else if (currentScreen === 'game') renderGame();
  else if (currentScreen === 'voting') renderVoting();
}

function renderNameInput() {
  app.innerHTML = '<div class="name-input"><h1>Chippy</h1><p class="subtitle">Enter your name to join</p><input id="nameInput" type="text" placeholder="Your name" maxlength="20" autofocus><button class="btn-primary" onclick="submitName()" style="max-width:300px">Join Game</button></div>';
  const input = document.getElementById('nameInput');
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter') submitName(); });
}

function submitName() {
  const input = document.getElementById('nameInput');
  const name = (input.value || '').trim();
  if (name) setName(name);
}

function renderHome() {
  const lobbies = state ? state.lobbies || {} : {};
  const peers = state ? state.discoveredPeers || {} : {};

  // Find lobbies we can join (2+ players, we're not in them)
  const foreignLobbies = Object.values(lobbies).filter(l =>
    Object.keys(l.players || {}).length > 1 && !(localId in (l.players || {}))
  );
  const foreignPlayerIds = new Set();
  foreignLobbies.forEach(l => Object.keys(l.players || {}).forEach(id => foreignPlayerIds.add(id)));

  // Available solo peers (not us, not in a foreign lobby)
  const availablePeers = Object.values(peers).filter(p => p.id !== localId && !foreignPlayerIds.has(p.id));

  let html = '<div class="header"><div><h1>Chippy</h1><p class="subtitle">Playing as: ' + esc(playerName) + '</p></div></div>';

  if (foreignLobbies.length > 0) {
    html += '<h2>Lobbies</h2>';
    foreignLobbies.forEach(lobby => {
      const players = Object.entries(lobby.players || {});
      const names = players.map(([,p]) => esc(p.name)).join(', ');
      const firstId = players[0] ? players[0][0] : '';
      html += '<div class="card" onclick="joinLobby(\'' + esc(lobby.lobbyId) + '\')" style="cursor:pointer"><div style="display:flex;justify-content:space-between;align-items:center"><span class="subtitle">' + players.length + ' players connected</span><span style="background:#bb86fc;color:#121212;padding:4px 12px;border-radius:12px;font-size:12px;font-weight:600">Join</span></div><p style="margin-top:8px">' + names + '</p></div>';
    });
  }

  if (availablePeers.length > 0) {
    html += '<h2>Nearby Players</h2>';
    availablePeers.forEach(peer => {
      // Find the lobby they're in (their self-lobby)
      const peerLobby = Object.values(lobbies).find(l => peer.id in (l.players || {}));
      const lobbyId = peerLobby ? peerLobby.lobbyId : peer.id;
      html += '<div class="player-row" onclick="joinLobby(\'' + esc(lobbyId) + '\')" style="cursor:pointer"><div class="avatar avatar-waiting">' + esc(peer.name.charAt(0).toUpperCase()) + '</div><div style="flex:1"><div style="font-weight:500">' + esc(peer.name) + '</div><div class="subtitle">Tap to join</div></div><span style="font-size:24px;color:#bb86fc">+</span></div>';
    });
  } else if (foreignLobbies.length === 0) {
    html += '<div class="card-muted">Searching for nearby players...</div>';
  }

  app.innerHTML = html;
}

function joinLobby(lobbyId) {
  send({ type: 'com.woutwerkman.net.PeerEvent.JoinedLobby', lobbyId, playerId: localId });
}

function renderLobby() {
  const lobby = getMyLobby();
  if (!lobby) return;
  const players = Object.entries(lobby.players || {});
  const me = lobby.players[localId];
  const isReady = me ? me.isReady : false;
  const countdown = lobby.countdownValue;

  let html = '<div class="header"><div><h1>Lobby</h1><p class="subtitle">' + players.length + ' player' + (players.length !== 1 ? 's' : '') + '</p></div><button class="btn-danger" onclick="leaveLobby()">Leave</button></div>';

  if (countdown != null) {
    html += '<div class="card" style="text-align:center;background:#333"><p style="font-size:18px;color:#aaa">Game starting in</p><p style="font-size:64px;font-weight:bold;color:#bb86fc">' + countdown + '</p></div>';
  }

  html += '<h2>Players</h2>';
  players.forEach(([pid, p]) => {
    const ready = p.isReady;
    html += '<div class="player-row"><div class="avatar ' + (ready ? 'avatar-ready' : 'avatar-waiting') + '">' + (ready ? '&#10003;' : esc(p.name.charAt(0).toUpperCase())) + '</div><div style="flex:1"><div style="font-weight:500">' + esc(p.name) + (pid === localId ? ' <span class="badge">(You)</span>' : '') + '</div><div class="status ' + (ready ? 'status-ready' : 'status-waiting') + '">' + (ready ? 'Ready' : 'Not ready') + '</div></div></div>';
  });

  html += '<div class="spacer"></div>';
  html += '<button class="' + (isReady ? 'btn-green' : 'btn-primary') + '" onclick="toggleReady()" ' + (countdown != null ? 'disabled' : '') + '>' + (isReady ? 'Ready!' : 'Ready Up') + '</button>';
  app.innerHTML = html;
}

function toggleReady() {
  const lobby = getMyLobby();
  if (!lobby) return;
  const me = lobby.players[localId];
  const isReady = me ? me.isReady : false;
  send({ type: 'com.woutwerkman.net.PeerEvent.ReadyChanged', lobbyId: lobby.lobbyId, playerId: localId, isReady: !isReady });
}

function leaveLobby() {
  const lobby = getMyLobby();
  if (!lobby) return;
  send({ type: 'com.woutwerkman.net.PeerEvent.LeftLobby', lobbyId: lobby.lobbyId, playerId: localId });
  currentScreen = 'home';
  render();
}

function renderGame() {
  const lobby = getMyLobby();
  if (!lobby) return;
  const values = lobby.playerValues || {};
  const players = lobby.players || {};
  const phase = lobby.gamePhase;
  const countdown = lobby.countdownValue;
  const enabled = phase === 'PLAYING';

  let html = '<div style="text-align:center;margin-bottom:8px"><h2>Get all to zero!</h2></div>';
  html += '<div class="instructions">Your button: -2 &nbsp;|&nbsp; Others: +1</div>';
  html += '<div class="game-grid">';

  Object.entries(values).forEach(([pid, val]) => {
    const name = players[pid] ? players[pid].name : 'Unknown';
    const isLocal = pid === localId;
    const abs = Math.abs(val);
    const greenness = 1 - abs / 25;
    const r = Math.round(229 - greenness * 153);
    const g = Math.round(115 + greenness * 60);
    const b = Math.round(115 - greenness * 35);
    const bg = val === 0 ? '#4CAF50' : 'rgb(' + r + ',' + g + ',' + b + ')';
    html += '<button class="game-btn" style="background:' + bg + '" onclick="pressButton(\'' + esc(pid) + '\')" ' + (enabled ? '' : 'disabled') + '><span class="name">' + esc(name) + '</span>' + val + (isLocal ? '<span class="you-badge">YOU</span>' : '') + '</button>';
  });

  html += '</div>';

  if (countdown != null) {
    const label = phase === 'COUNTDOWN' ? 'Get ready!' : phase === 'WIN_COUNTDOWN' ? 'ALL ZEROS!' : '';
    html += '<div class="countdown-overlay"><p class="' + (phase === 'WIN_COUNTDOWN' ? 'win-label' : 'label') + '">' + label + '</p><p class="number">' + countdown + '</p></div>';
  }

  app.innerHTML = html;
}

function pressButton(targetId) {
  const lobby = getMyLobby();
  if (!lobby || lobby.gamePhase !== 'PLAYING') return;
  const delta = targetId === localId ? -2 : 1;
  send({ type: 'com.woutwerkman.net.PeerEvent.ButtonPress', lobbyId: lobby.lobbyId, sourceId: localId, targetId, delta });
}

function renderVoting() {
  const lobby = getMyLobby();
  if (!lobby) return;
  const votes = lobby.votes || {};
  const players = lobby.players || {};
  const totalPlayers = Object.keys(players).length;
  const totalVotes = Object.keys(votes).length;
  const myVote = votes[localId] || null;
  const hasVoted = myVote !== null;
  const playAgainCount = Object.values(votes).filter(v => v === 'PLAY_AGAIN').length;
  const endLobbyCount = Object.values(votes).filter(v => v === 'END_LOBBY').length;

  let html = '<div style="text-align:center;margin:32px 0"><p style="font-size:64px">&#127881;</p><h1 style="color:#4CAF50;margin:16px 0">Victory!</h1><p class="subtitle">All buttons reached zero!</p></div>';
  html += '<h2 style="text-align:center">What would you like to do?</h2>';
  html += '<div class="vote-row">';
  html += '<div class="vote-card' + (myVote === 'PLAY_AGAIN' ? ' selected' : '') + '"><div class="emoji">&#128260;</div><div class="label">Play Again</div><div class="count">' + playAgainCount + ' vote' + (playAgainCount !== 1 ? 's' : '') + '</div><button class="btn-primary" onclick="vote(\'PLAY_AGAIN\')" ' + (hasVoted ? 'disabled' : '') + '>' + (myVote === 'PLAY_AGAIN' ? 'Voted' : 'Vote') + '</button></div>';
  html += '<div class="vote-card' + (myVote === 'END_LOBBY' ? ' selected' : '') + '"><div class="emoji">&#127968;</div><div class="label">End Lobby</div><div class="count">' + endLobbyCount + ' vote' + (endLobbyCount !== 1 ? 's' : '') + '</div><button class="btn-primary" onclick="vote(\'END_LOBBY\')" ' + (hasVoted ? 'disabled' : '') + '>' + (myVote === 'END_LOBBY' ? 'Voted' : 'Vote') + '</button></div>';
  html += '</div>';

  html += '<div class="card"><p style="text-align:center;font-weight:500">Votes: ' + totalVotes + ' / ' + totalPlayers + '</p><div class="progress-bar"><div class="progress-fill" style="width:' + (totalPlayers > 0 ? (totalVotes / totalPlayers * 100) : 0) + '%"></div></div>';
  if (totalVotes < totalPlayers) html += '<p style="text-align:center;font-size:12px;color:#aaa">Waiting for ' + (totalPlayers - totalVotes) + ' more vote' + (totalPlayers - totalVotes !== 1 ? 's' : '') + '...</p>';
  html += '</div>';

  html += '<h2 style="margin-top:24px">Player Votes</h2>';
  Object.entries(players).forEach(([pid, p]) => {
    const v = votes[pid] || null;
    const icon = v === 'PLAY_AGAIN' ? '&#128260;' : v === 'END_LOBBY' ? '&#127968;' : '?';
    const statusText = v === 'PLAY_AGAIN' ? 'Wants to play again' : v === 'END_LOBBY' ? 'Wants to end lobby' : "Hasn't voted yet";
    html += '<div class="player-row"><div class="avatar" style="background:' + (v === 'PLAY_AGAIN' ? '#4CAF50' : v === 'END_LOBBY' ? '#FF9800' : '#555') + ';color:white;font-size:14px">' + icon + '</div><div style="flex:1"><div style="font-weight:500">' + esc(p.name) + (pid === localId ? ' <span class="badge">(You)</span>' : '') + '</div><div class="subtitle">' + statusText + '</div></div></div>';
  });

  app.innerHTML = html;
}

function vote(choice) {
  const lobby = getMyLobby();
  if (!lobby) return;
  send({ type: 'com.woutwerkman.net.PeerEvent.VoteCast', lobbyId: lobby.lobbyId, playerId: localId, choice });
}

function esc(s) { const d = document.createElement('div'); d.textContent = s; return d.innerHTML; }

connect();
</script>
</body>
</html>
""".trimIndent()
