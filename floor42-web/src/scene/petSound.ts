/**
 * The cat's meow and the dog's bark, synthesised on the spot with WebAudio: no audio file, nothing downloaded. Off
 * until the director turns it on (the UI keeps that choice in the browser). Any failure (no audio, a blocked context) is
 * swallowed: a missing sound must never break the scene.
 */
export class PetVoice {
  private on = false
  private ctx: AudioContext | null = null

  get enabled(): boolean {
    return this.on
  }

  /** Turning it off closes the audio context; turning it on makes none: that waits for {@link unlock}. */
  set enabled(value: boolean) {
    this.on = value
    if (!value) this.dispose()
  }

  /**
   * Creates (or resumes) the audio context. Call it only from a user gesture (the switch, the first click on the
   * page): a context made from the frame loop stays suspended and plays nothing. Until then sounds are skipped.
   */
  unlock(): void {
    if (!this.on) return
    try {
      const ctx = this.context()
      if (ctx && ctx.state === 'suspended') void ctx.resume()?.catch?.(() => {})
    } catch {
      // No sound is better than a broken scene.
    }
  }

  /** Plays the sound for a tag ('meow' or 'bark'); nothing otherwise, or while off. */
  play(tag: string): void {
    if (!this.on || !this.ctx) return
    try {
      const ctx = this.ctx
      if (ctx.state === 'suspended') return
      const now = ctx.currentTime
      if (tag === 'meow') {
        this.tone(ctx, 'triangle', [[0, 520], [0.12, 900], [0.42, 460]], now, 0.45, 0.1)
      } else if (tag === 'bark') {
        this.tone(ctx, 'sawtooth', [[0, 300], [0.1, 120]], now, 0.12, 0.12)
        this.tone(ctx, 'sawtooth', [[0, 280], [0.1, 110]], now + 0.18, 0.12, 0.12)
      }
    } catch {
      // No sound is better than a broken scene.
    }
  }

  dispose(): void {
    try {
      void this.ctx?.close()?.catch?.(() => {})
    } catch {
      // Already closed.
    }
    this.ctx = null
  }

  private context(): AudioContext | null {
    if (this.ctx) return this.ctx
    const Ctor = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
    if (!Ctor) return null
    this.ctx = new Ctor()
    return this.ctx
  }

  /** One voice: an oscillator sliding through `pitch` (seconds, Hz) under a soft envelope and a low-pass. */
  private tone(ctx: AudioContext, type: OscillatorType, pitch: [number, number][], at: number, length: number, volume: number): void {
    const osc = ctx.createOscillator()
    const gain = ctx.createGain()
    const filter = ctx.createBiquadFilter()
    osc.type = type
    filter.type = 'lowpass'
    filter.frequency.value = 1400
    osc.frequency.setValueAtTime(pitch[0][1], at + pitch[0][0])
    for (const [s, hz] of pitch.slice(1)) osc.frequency.linearRampToValueAtTime(hz, at + s)
    gain.gain.setValueAtTime(0.0001, at)
    gain.gain.linearRampToValueAtTime(volume, at + length * 0.2)
    gain.gain.linearRampToValueAtTime(0.0001, at + length)
    osc.connect(filter).connect(gain).connect(ctx.destination)
    osc.start(at)
    osc.stop(at + length + 0.02)
  }
}
