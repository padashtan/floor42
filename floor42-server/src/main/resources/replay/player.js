/*
 * Floor42 mission replay player (ADR-0035). A plain script written into each exported file: it reads the data element
 * of the file and plays it. It loads nothing and sends nothing (the file's Content-Security-Policy forbids it). Every
 * line it shows is an event as the journal recorded it, with its origin; it adds no event and computes no estimate.
 * The pure parts (timeline, player, state) are tested by floor42-web/src/replayExport/player.test.ts.
 */
var OfficeReplay = (function () {
  'use strict'

  /** Playback offset (ms) of each event: the recorded gaps, each shortened to maxGapMs at most. */
  function timeline(events, maxGapMs) {
    var offsets = []
    var at = 0
    var prev = null
    for (var i = 0; i < events.length; i++) {
      var t = Date.parse(events[i].at)
      if (prev !== null && !isNaN(t)) at += Math.min(Math.max(t - prev, 0), maxGapMs)
      offsets.push(at)
      if (!isNaN(t)) prev = t
    }
    return { offsets: offsets, duration: at }
  }

  /** How many events are on screen at a playback position: those whose offset is reached. */
  function countAt(offsets, position) {
    var lo = 0
    var hi = offsets.length
    while (lo < hi) {
      var mid = (lo + hi) >> 1
      if (offsets[mid] <= position) lo = mid + 1
      else hi = mid
    }
    return lo
  }

  var TICKET_STATUS = {
    TicketCreated: 'todo',
    TicketApproved: 'todo',
    TicketReopened: 'todo',
    TicketReleased: 'todo',
    TicketStarted: 'inProgress',
    TicketSubmittedForReview: 'inReview',
    TicketCompleted: 'done',
    TicketCancelled: 'cancelled',
  }

  /** The board, the desks and the counts after the first `count` events: only what those events say. */
  function stateAt(data, count) {
    var titles = {}
    ;(data.tickets || []).forEach(function (t) {
      titles[t.id] = t.title
    })
    var tickets = {}
    var order = []
    var agents = {}
    var origins = { REAL: 0, SIMULATED: 0 }
    var cost = 0
    var costs = 0
    var shown = data.events.slice(0, count)
    shown.forEach(function (e) {
      origins[e.origin] = (origins[e.origin] || 0) + 1
      var status = TICKET_STATUS[e.type]
      if (status && e.ticket) {
        if (!tickets[e.ticket]) order.push(e.ticket)
        tickets[e.ticket] = { id: e.ticket, title: titles[e.ticket] || e.ticket, status: status }
      }
      if (e.agent) agents[e.agent] = { last: e, ran: e.ran }
      if (e.cost) {
        cost += e.cost.usd
        costs++
      }
    })
    return {
      tickets: order.map(function (id) {
        return tickets[id]
      }),
      agents: agents,
      origins: origins,
      cost: costs ? cost : null,
      costs: costs,
      last: shown.length ? shown[shown.length - 1] : null,
    }
  }

  /** The clock of the replay: a position in playback time, a speed, playing or not. */
  function Player(data, clock) {
    this.clock =
      clock ||
      function () {
        return Date.now()
      }
    var tl = timeline(data.events, data.maxGapMs || 2000)
    this.offsets = tl.offsets
    this.duration = tl.duration
    this.position = 0
    this.since = this.clock()
    this.speed = 1
    this.playing = false
  }

  Player.prototype.now = function () {
    if (!this.playing) return this.position
    return Math.min(this.duration, this.position + (this.clock() - this.since) * this.speed)
  }

  Player.prototype.play = function () {
    if (this.position >= this.duration) this.position = 0
    this.since = this.clock()
    this.playing = true
  }

  Player.prototype.pause = function () {
    this.position = this.now()
    this.playing = false
  }

  Player.prototype.setSpeed = function (speed) {
    this.position = this.now()
    this.since = this.clock()
    this.speed = speed > 0 ? speed : 1
  }

  Player.prototype.seek = function (position) {
    this.position = Math.max(0, Math.min(this.duration, position))
    this.since = this.clock()
  }

  /** Puts the replay on its n-th event (1 = the first). */
  Player.prototype.seekEvent = function (n) {
    if (!this.offsets.length) return
    var i = Math.max(1, Math.min(this.offsets.length, n)) - 1
    this.seek(this.offsets[i])
  }

  /** The number of events on screen now; stops at the end. */
  Player.prototype.count = function () {
    var at = this.now()
    if (this.playing && at >= this.duration) {
      this.position = this.duration
      this.playing = false
    }
    return countAt(this.offsets, at)
  }

  // ---- the page ---------------------------------------------------------------------------------------------------

  function label(data, key, params) {
    var text = (data.labels && data.labels[key]) || key
    return text.replace(/\{(\w+)\}/g, function (all, name) {
      return params && name in params ? String(params[name]) : all
    })
  }

  function el(tag, cls, text) {
    var e = document.createElement(tag)
    if (cls) e.className = cls
    if (text !== undefined && text !== null) e.textContent = text
    return e
  }

  function originBadge(data, origin) {
    var real = origin === 'REAL'
    var b = el('span', 'badge ' + (real ? 'real' : 'simulated'), label(data, real ? 'real' : 'simulated'))
    b.title = label(data, real ? 'realTip' : 'simulatedTip')
    return b
  }

  function when(at) {
    var d = new Date(at)
    return isNaN(d.getTime()) ? at : d.toLocaleString()
  }

  function boot() {
    var data = JSON.parse(document.getElementById('replay-data').textContent)
    var root = document.getElementById('app')
    var player = new Player(data, function () {
      return performance.now()
    })
    var L = function (k, p) {
      return label(data, k, p)
    }

    var head = el('header', 'head')
    var h1 = el('h1', null, data.mission.id + ' · ' + data.mission.title)
    h1.appendChild(document.createTextNode(' '))
    h1.appendChild(originBadge(data, data.mission.origin))
    head.appendChild(h1)
    head.appendChild(
      el(
        'div',
        'hint',
        L('header', {
          version: data.header.version,
          mission: data.header.mission,
          at: when(data.header.exportedAt),
        }),
      ),
    )
    if (data.mission.objective) head.appendChild(el('div', 'objective', data.mission.objective))
    root.appendChild(head)

    var controls = el('div', 'controls')
    var back = el('button', null, '⏮')
    back.title = L('back')
    var playBtn = el('button', 'play', L('play'))
    var fwd = el('button', null, '⏭')
    fwd.title = L('forward')
    var speed = el('select')
    speed.title = L('speed')
    ;[0.5, 1, 2, 4, 8, 16].forEach(function (s) {
      var o = el('option', null, '×' + s)
      o.value = String(s)
      if (s === 1) o.selected = true
      speed.appendChild(o)
    })
    var range = el('input')
    range.type = 'range'
    range.min = '0'
    range.max = String(Math.max(1, player.duration))
    range.step = '1'
    var position = el('span', 'position')
    ;[back, playBtn, fwd, speed, range, position].forEach(function (c) {
      controls.appendChild(c)
    })
    root.appendChild(controls)
    root.appendChild(el('div', 'hint pacing', L('pacing', { seconds: (data.maxGapMs || 2000) / 1000 })))

    var main = el('div', 'main')
    var floor = el('section', 'floor')
    floor.appendChild(el('h2', null, L('room')))
    var desks = el('div', 'desks')
    floor.appendChild(desks)
    var board = el('section', 'board')
    board.appendChild(el('h2', null, L('board')))
    var columns = el('div', 'columns')
    board.appendChild(columns)
    var log = el('section', 'log')
    log.appendChild(el('h2', null, L('log')))
    var list = el('ol', 'events')
    log.appendChild(list)
    main.appendChild(floor)
    main.appendChild(board)
    main.appendChild(log)
    root.appendChild(main)

    var deskOf = {}
    data.agents.forEach(function (a) {
      var d = el('div', 'desk')
      d.appendChild(el('div', 'screen'))
      d.appendChild(el('div', 'name', a.name))
      d.appendChild(el('div', 'role', a.role))
      var bubble = el('div', 'bubble')
      d.appendChild(bubble)
      desks.appendChild(d)
      deskOf[a.id] = { desk: d, bubble: bubble }
    })
    if (!data.agents.length) desks.appendChild(el('div', 'hint', L('noAgents')))

    var rendered = 0
    function row(e) {
      var li = el('li', 'event')
      var top = el('div', 'line')
      top.appendChild(el('span', 'seq', '#' + e.seq))
      top.appendChild(originBadge(data, e.origin))
      top.appendChild(el('span', 'time', when(e.at)))
      top.appendChild(el('span', 'type', e.type))
      li.appendChild(top)
      li.appendChild(el('div', 'text', e.text))
      if (e.cost) {
        li.appendChild(el('div', 'cost', L('cost', { usd: e.cost.usd.toFixed(4) })))
      }
      if (e.output) {
        var det = el('details')
        det.appendChild(el('summary', null, L('output') + (e.outputClipped ? ' · ' + L('clipped') : '')))
        det.appendChild(el('pre', null, e.output))
        li.appendChild(det)
      }
      return li
    }

    function render(count) {
      if (count < rendered) {
        list.textContent = ''
        rendered = 0
      }
      for (; rendered < count; rendered++) list.appendChild(row(data.events[rendered]))
      var current = list.lastChild
      if (current && current.scrollIntoView && player.playing) current.scrollIntoView({ block: 'nearest' })

      var s = stateAt(data, count)
      columns.textContent = ''
      ;['todo', 'inProgress', 'inReview', 'done', 'cancelled'].forEach(function (status) {
        var col = el('div', 'column')
        var items = s.tickets.filter(function (t) {
          return t.status === status
        })
        col.appendChild(el('h3', null, L(status) + ' · ' + items.length))
        items.forEach(function (t) {
          col.appendChild(el('div', 'card', t.id + ' ' + t.title))
        })
        columns.appendChild(col)
      })
      data.agents.forEach(function (a) {
        var d = deskOf[a.id]
        var st = s.agents[a.id]
        var current = st && s.last && st.last.seq === s.last.seq
        d.desk.className = 'desk' + (st ? ' seen' : '') + (current ? ' current' : '') + (current && st.ran ? ' ran' : '')
        d.bubble.textContent = st ? st.last.text : L('idle')
      })
      var total = data.events.length
      position.textContent =
        L('event', { n: count, total: total }) +
        (s.last ? ' · ' + when(s.last.at) : '') +
        ' · ' +
        L('origins', { real: s.origins.REAL || 0, simulated: s.origins.SIMULATED || 0 }) +
        (s.cost !== null ? ' · ' + L('costs', { usd: s.cost.toFixed(4), count: s.costs }) : '')
      playBtn.textContent = player.playing ? L('pause') : L('play')
      if (document.activeElement !== range) range.value = String(Math.round(player.now()))
    }

    playBtn.onclick = function () {
      if (player.playing) player.pause()
      else player.play()
      render(player.count())
    }
    back.onclick = function () {
      player.pause()
      player.seekEvent(player.count() - 1)
      render(player.count())
    }
    fwd.onclick = function () {
      player.pause()
      player.seekEvent(player.count() + 1)
      render(player.count())
    }
    speed.onchange = function () {
      player.setSpeed(Number(speed.value))
    }
    range.oninput = function () {
      player.seek(Number(range.value))
      render(player.count())
    }
    render(player.count())
    setInterval(function () {
      if (player.playing) render(player.count())
    }, 100)
  }

  if (typeof document !== 'undefined' && document.getElementById && document.getElementById('replay-data')) {
    boot()
  }

  return { timeline: timeline, countAt: countAt, stateAt: stateAt, Player: Player, label: label }
})()
