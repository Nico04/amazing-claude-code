import type { KeyCap } from './components/HotkeyCaps'
import type { PanelKeyName, PanelKeyStroke, VoiceHotkeyCap } from './protocol'
import { onMac } from './sendKey'

/**
 * The panel's keys as the IDE says they are bound right now (see PanelKeys.kt), by command. Missing until
 * the IDE has said - the harness, a page that has only just opened - and nothing is drawn for a key that
 * is not known: a guessed key on a hint is a key that does nothing for somebody with another keymap.
 */
export type PanelKeys = Partial<Record<PanelKeyName, PanelKeyStroke[]>>

/**
 * A key as one line of text, for a hover hint: "Cmd+Shift+]", "Alt+Left", "Ctrl+F4".
 *
 * Words rather than signs, for the reason the send key's hint gives (see sendKey.ts): ⌘ and ⌥ are not in
 * the panel's font, and a hint is a line of text with nowhere to put a drawing. The cheat sheet draws the
 * same keys as caps (see HotkeyCaps). Empty when the command has no key, so a caller can leave it out.
 */
export const keyText = (strokes: PanelKeyStroke[] | undefined): string =>
  (strokes ?? []).map((stroke) => stroke.map(capWord).join('+')).join(', ')

const capWord = (cap: VoiceHotkeyCap | KeyCap): string => {
  switch (cap.glyph) {
    case 'command':
      return 'Cmd'
    case 'option':
      return 'Option'
    case 'win':
      return 'Win'
    case 'left':
      return 'Left'
    case 'right':
      return 'Right'
    case 'up':
      return 'Up'
    case 'down':
      return 'Down'
    default:
      return cap.text ?? ''
  }
}

/**
 * The keys the page itself listens for, as caps for the cheat sheet - the IDE never names these, they
 * are not in its keymap. A Mac draws ⌘ and ⌥ and spells Shift out; elsewhere every modifier is a word,
 * the same split the IDE makes for its own (see HotkeyBinding.kt).
 */
export const pageCaps = {
  key: (text: string): KeyCap => ({ glyph: '', text }),
  arrow: (glyph: 'up' | 'down'): KeyCap => ({ glyph }),
  /**
   * Keys pressed together, in the order the IDE draws its own beside them (see HotkeyBinding.caps): Ctrl,
   * Alt, Shift, then Cmd. On one screen a Mac then reads Shift-⌘-Z for redo the way it reads Shift-⌘-] for
   * the next tab, and elsewhere it is Ctrl+Shift+Z, Ctrl first.
   */
  chord: (mods: ('mod' | 'alt' | 'shift')[], key: KeyCap): KeyCap[] => {
    const mac = onMac()
    const caps: KeyCap[] = []
    if (!mac && mods.includes('mod')) caps.push({ glyph: '', text: 'Ctrl' })
    if (mods.includes('alt')) caps.push(mac ? { glyph: 'option' } : { glyph: '', text: 'Alt' })
    if (mods.includes('shift')) caps.push({ glyph: '', text: 'Shift' })
    if (mac && mods.includes('mod')) caps.push({ glyph: 'command' })
    return [...caps, key]
  },
}
