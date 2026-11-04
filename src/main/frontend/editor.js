// Minimal JSON code editor used by the bucket-policy and CORS dialogs.
import { EditorView, basicSetup } from 'codemirror';
import { Compartment, Transaction } from '@codemirror/state';
import { json, jsonParseLinter } from '@codemirror/lang-json';
import { linter, lintGutter } from '@codemirror/lint';
import { oneDark } from '@codemirror/theme-one-dark';

const heightCap = EditorView.theme({
  '&': { maxHeight: '24rem', fontSize: '0.8125rem' },
  '.cm-scroller': { overflow: 'auto', fontFamily: 'var(--font-mono, ui-monospace, monospace)' },
});

const isDark = () => document.documentElement.dataset.theme === 'dark';
const themeExtension = () => (isDark() ? oneDark : []);

/**
 * Mount a JSON editor into `host` seeded with `doc`. Returns handles for reading/replacing the
 * document and tearing the view down; the editor follows the app's light/dark theme.
 */
export function createJsonEditor(host, doc = '', { onChange } = {}) {
  const themeCompartment = new Compartment();
  const editableCompartment = new Compartment();
  const view = new EditorView({
    doc,
    parent: host,
    extensions: [
      basicSetup,
      json(),
      lintGutter(),
      linter(jsonParseLinter()),
      heightCap,
      themeCompartment.of(themeExtension()),
      editableCompartment.of(EditorView.editable.of(true)),
      // Only user edits count: programmatic setValue() calls carry no userEvent.
      EditorView.updateListener.of((update) => {
        if (onChange && update.docChanged && update.transactions.some((tr) => tr.annotation(Transaction.userEvent)))
          onChange();
      }),
    ],
  });

  const onThemeChange = () =>
    view.dispatch({ effects: themeCompartment.reconfigure(themeExtension()) });
  document.addEventListener('s3webui:themechange', onThemeChange);

  return {
    view,
    getValue: () => view.state.doc.toString(),
    setValue: (value) =>
      view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: value ?? '' } }),
    /** Moves the cursor to a character offset, scrolls it into view and focuses the editor. */
    focusAt: (offset) => {
      const position = Math.max(0, Math.min(offset ?? 0, view.state.doc.length));
      view.requestMeasure();
      view.dispatch({ selection: { anchor: position }, scrollIntoView: true });
      view.focus();
    },
    setEditable: (editable) =>
      view.dispatch({ effects: editableCompartment.reconfigure(EditorView.editable.of(editable)) }),
    destroy: () => {
      document.removeEventListener('s3webui:themechange', onThemeChange);
      view.destroy();
    },
  };
}
