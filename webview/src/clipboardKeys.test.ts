import { describe, expect, it } from 'vitest'
import { TYPING_WINDOW_MS, isLeak, isPasteShortcut, isTyping, servesPaste, typedNothing } from './clipboardKeys'

const key = (over: Partial<Parameters<typeof isPasteShortcut>[0]> = {}) => ({
  code: 'KeyV',
  ctrlKey: false,
  metaKey: false,
  shiftKey: false,
  altKey: false,
  ...over,
})

describe('isPasteShortcut', () => {
  it('takes Ctrl+V and Cmd+V', () => {
    expect(isPasteShortcut(key({ ctrlKey: true }))).toBe(true)
    expect(isPasteShortcut(key({ metaKey: true }))).toBe(true)
  })

  it('takes Shift+Insert, the other habit on Linux', () => {
    expect(isPasteShortcut(key({ code: 'Insert', shiftKey: true }))).toBe(true)
  })

  it("takes Ctrl+Shift+V, the terminal's paste", () => {
    // It used to be refused, and on Linux it typed a capital V instead of pasting.
    expect(isPasteShortcut(key({ ctrlKey: true, shiftKey: true }))).toBe(true)
  })

  it('leaves a plain letter alone', () => {
    expect(isPasteShortcut(key())).toBe(false)
    expect(isPasteShortcut(key({ shiftKey: true }))).toBe(false)
  })

  it('leaves the shortcuts that mean something else alone', () => {
    expect(isPasteShortcut(key({ ctrlKey: true, altKey: true }))).toBe(false)
    expect(isPasteShortcut(key({ code: 'Insert', shiftKey: true, ctrlKey: true }))).toBe(false)
  })
})

describe('servesPaste', () => {
  it('serves a paste nobody pressed a key for - that is the context menu', () => {
    expect(servesPaste(null, 1_000)).toBe(true)
  })

  it('serves a paste that follows its shortcut', () => {
    expect(servesPaste({ at: 1_000, shortcut: true, text: '' }, 1_010)).toBe(true)
  })

  it('refuses a paste that follows an ordinary key press', () => {
    // Shift+V on Linux: the press crosses into the browser carrying modifiers nobody held, and comes
    // back as an empty paste. Filling that in is how the clipboard landed in a message by itself.
    expect(servesPaste({ at: 1_000, shortcut: false, text: 'V' }, 1_010)).toBe(false)
  })

  it('stops blaming a press once it is long past', () => {
    expect(servesPaste({ at: 1_000, shortcut: false, text: 'V' }, 1_000 + TYPING_WINDOW_MS + 1)).toBe(true)
  })
})

describe('typedNothing', () => {
  it('is true for a press that put no character in', () => {
    expect(typedNothing(1_000, null)).toBe(true)
    expect(typedNothing(1_000, 900)).toBe(true)
  })

  it('is false for a press that turned out to type a character', () => {
    expect(typedNothing(1_000, 1_005)).toBe(false)
  })

  it('ignores typing that comes long after the press', () => {
    expect(typedNothing(1_000, 1_000 + TYPING_WINDOW_MS + 1)).toBe(true)
  })
})

describe('isLeak', () => {
  const owned = { at: 1_000, text: 'v' }

  it("swallows the paste shortcut's own letter right after the press", () => {
    // Ubuntu, Wayland, German layout: Ctrl+V also typed a "v", and the "v" cost the paste.
    expect(isLeak(owned, 'v', 1_005)).toBe(true)
    expect(isLeak({ at: 1_000, text: 'V' }, 'V', 1_005)).toBe(true)
    expect(isLeak(owned, 'V', 1_005)).toBe(true)
  })

  it('lets any other text through', () => {
    expect(isLeak(owned, 'x', 1_005)).toBe(false)
    expect(isLeak(owned, 'vv', 1_005)).toBe(false)
    expect(isLeak(owned, null, 1_005)).toBe(false)
  })

  it('lets the letter through once the press is long past', () => {
    expect(isLeak(owned, 'v', 1_000 + TYPING_WINDOW_MS + 1)).toBe(false)
  })

  it('has nothing to swallow without a press, or for a key that writes nothing', () => {
    expect(isLeak(null, 'v', 1_005)).toBe(false)
    expect(isLeak({ at: 1_000, text: '' }, 'v', 1_005)).toBe(false)
  })
})

describe('isTyping', () => {
  it('counts what a keyboard and an input method put in', () => {
    expect(isTyping('insertText')).toBe(true)
    expect(isTyping('insertCompositionText')).toBe(true)
    expect(isTyping('insertLineBreak')).toBe(true)
  })

  it('does not count the paste itself', () => {
    expect(isTyping('insertFromPaste')).toBe(false)
    expect(isTyping('insertFromPasteAsQuotation')).toBe(false)
  })

  it('does not count deleting', () => {
    expect(isTyping('deleteContentBackward')).toBe(false)
  })
})
