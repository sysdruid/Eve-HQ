package eve.hq.app

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/** Puts [text] on the system clipboard, for pasting into EVE's multibuy window. */
internal fun copyToClipboard(text: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}
