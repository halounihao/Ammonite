package ammonite.terminal


import scala.annotation.tailrec

import ammonite.terminal.LazyList._


// Test Unicode:  漢語;𩶘da
object Term{
  def main(args: Array[String]): Unit = {
    var history = List.empty[String]
    rec()
    @tailrec def rec(): Unit = {
      TermCore.readLine(
        "@ ",
        System.in,
        System.out,
        readlineNavFilter orElse
        new ReadlineEditFilter() orElse
        // Example multiline support by intercepting Enter key
        new HistoryFilter(history) orElse
        multilineFilter orElse
        defaultFilter,
        // Example displayTransform: underline all non-spaces
        displayTransform = (buffer, cursor) => {
          val buffer2 = buffer.flatMap{
            case ' ' => " "
            case '\n' => "\n"
            case c => Console.UNDERLINED + c + Console.RESET
          }
          (buffer2 , cursor)
        }
      ) match {
        case None => println("Bye!")
        case Some(s) =>
          history = s :: history
          println(s)
          rec()
      }
    }
  }

  def firstRow(cursor: Int, buffer: Vector[Char], width: Int) = {
    cursor < width && (buffer.indexOf('\n') >= cursor || buffer.indexOf('\n') == -1)
  }
  def lastRow(cursor: Int, buffer: Vector[Char], width: Int) = {
    (buffer.length - cursor) < width && (buffer.lastIndexOf('\n') < cursor || buffer.lastIndexOf('\n') == -1)
  }
  class HistoryFilter(history: => Seq[String]) extends TermCore.DelegateFilter{
    var index = -1
    var currentHistory = Vector[Char]()

    def continue(b: Vector[Char], newIndex: Int, rest: LazyList[Int], c: Int) = {
      if (index == -1 && newIndex != -1) currentHistory = b

      index = newIndex

      if (index == -1) TS(rest, currentHistory, c)
      else TS(rest, history(index).toVector, c)
    }
    def filter = {
      case TI(TS(p"\u001b[A$rest", b, c), w) if firstRow(c, b, w) =>
        continue(b, (index + 1) min (history.length - 1), rest, 99999)
      case TI(TS(p"\u001b[B$rest", b, c), w) if lastRow(c, b, w) =>
        continue(b, (index - 1) max -1, rest, 0)
    }
  }
  val TS = TermState
  val TI = TermInfo
  val multilineFilter: TermCore.Filter = {
    case TS(13 ~: rest, b, c) =>
      val open = b.count(_ == '(')
      val close = b.count(_ == ')')
      Debug(open + "\t" + close)
      if (open == close) Result(b.mkString)
      else {
        val (first, last) = b.splitAt(c)
        TermState(rest, (first :+ '\n') ++ last, c + 1)
      }
  }
  val defaultFilter = {
    advancedNavFilter orElse
    basicNavFilter orElse
    exitFilter orElse
    enterFilter orElse
    loggingFilter orElse
    typingFilter
  }


  lazy val loggingFilter: TermCore.Filter = {
    case TS(Ctrl('t') ~: rest, b, c) =>
      println("Char Display Mode Enabled! Ctrl-C to exit")
      var curr = rest
      while (curr.head != 3) {
        println("Char " + curr.head)
        curr = curr.tail
      }
      TS(curr, b, c)
  }
  lazy val typingFilter: TermCore.Filter = {
    case TS(p"\u001b[3~$rest", b, c) =>
      Debug("fn-delete")
      val (first, last) = b.splitAt(c)
      TS(rest, first ++ last.drop(1), c)

    case TS(127 ~: rest, b, c) => // Backspace
      val (first, last) = b.splitAt(c)
      TS(rest, first.dropRight(1) ++ last, c - 1)

    case TS(char ~: rest, b, c) =>
      Debug("NORMAL CHAR " + char)
      val (first, last) = b.splitAt(c)
      TS(rest, (first :+ char.toChar) ++ last, c + 1)
  }

  lazy val enterFilter: TermCore.Filter = {
    case TS(13 ~: rest, b, c) => // Enter
      Result(b.mkString)
  }
  lazy val exitFilter: TermCore.Filter = {
    case TS(Ctrl('c') ~: rest, b, c) =>
      TS(rest, Vector.empty, 0)
    case TS(Ctrl('d') ~: rest, b, c) => Exit
  }

  // TODO: implement all the readline shortcuts
  // www.bigsmoke.us/readline/shortcuts
  // Ctrl-b     <- one char
  // Ctrl-f     -> one char
  // Alt-b      <- one word
  // Alt-f      -> one word
  // Ctrl-a     <- start of line
  // Ctrl-e     -> end of line
  // Ctrl-x-x   Toggle start/end

  // Backspace  <- delete char
  // Del        -> delete char
  // Ctrl-u     <- delete all
  // Ctrl-k     -> delete all
  // Alt-d      -> delete word
  // Ctrl-w     <- delete word

  // Ctrl-u/-   Undo
  // Ctrl-l     clear screen

  // Ctrl-k     -> cut all
  // Alt-d      -> cut word
  // Alt-Backspace  <- cut word
  // Ctrl-y     paste last cut

  /**
   * Lets you easily pattern match on characters modified by ctrl
   */
  object Ctrl{
    def unapply(i: Int): Option[Int] = Some(i + 96)
  }

  def readlineNavFilter: TermCore.Filter = {
    case TS(Ctrl('b') ~: rest, b, c) => TS(rest, b, c - 1) // <- one char
    case TS(Ctrl('f') ~: rest, b, c) => TS(rest, b, c + 1) // -> one char
    case TS(p"\u001bb$rest", b, c) => TS(rest, b, consumeWord(b, c, -1, 1)) // Alt-b <- one word
    case TS(p"\u001bf$rest", b, c) => TS(rest, b, consumeWord(b, c, 1, 0)) // Alt-f -> one word
    case TI(TS(Ctrl('a') ~: rest, b, c), w) => TS(rest, b, moveStart(b, c, w)) // Ctrl-a <- one line
    case TI(TS(Ctrl('e') ~: rest, b, c), w) => TS(rest, b, moveEnd(b, c, w)) // Ctrl-e -> one line
  }

  class ReadlineEditFilter() extends TermCore.DelegateFilter{
    var currentCut = Vector.empty[Char]
    def cutAllLeft(b: Vector[Char], c: Int) = {
      currentCut = b.take(c)
      (b.drop(c), 0)
    }
    def cutAllRight(b: Vector[Char], c: Int) = {
      currentCut = b.drop(c)
      (b.take(c), c)
    }

    def cutWordRight(b: Vector[Char], c: Int) = {
      val start = consumeWord(b, c, 1, 0)
      currentCut = b.slice(c, start)
      (b.take(c) ++ b.drop(start), c)
    }

    def cutWordLeft(b: Vector[Char], c: Int) = {
      val start = consumeWord(b, c, -1, 1)
      currentCut = b.slice(start, c)
      (b.take(start) ++ b.drop(c), start)
    }


    def filter = {
      case TS(Ctrl('u') ~: rest, b, c) => // <- delete all
        val (b1, c1) = cutAllLeft(b, c)
        TS(rest, b1, c1)
      case TS(Ctrl('k') ~: rest, b, c) => // -> delete all
        val (b1, c1) = cutAllRight(b, c)
        TS(rest, b1, c1)
      case TS(p"\u001bd$rest", b, c) => // -> delete word
        val (b1, c1) = cutWordRight(b, c)
        TS(rest, b1, c1)
      case TS(Ctrl('w') ~: rest, b, c) => // <- delete word
        val (b1, c1) = cutWordLeft(b, c)
        TS(rest, b1, c1)
      case TS(Ctrl('y') ~: rest, b, c) => // paste last cut
        TS(rest, b.take(c) ++ currentCut ++ b.drop(c), c + currentCut.length)
    }

  }
  def consumeWord(b: Vector[Char], c: Int, delta: Int, offset: Int) = {
    var current = c
    // Move at least one character! Otherwise
    // you get stuck at the end of a word.
    current += delta
    while(b.isDefinedAt(current) && !b(current).isLetterOrDigit) current += delta
    while(b.isDefinedAt(current) && b(current).isLetterOrDigit) current += delta
    current + offset
  }

  def findChunks(b: Vector[Char], c: Int) = {
    val chunks = TermCore.splitBuffer(b)
    // The index of the first character in each chunk
    val chunkStarts = chunks.inits.map(x => x.length + x.sum).toStream.reverse
    // Index of the current chunk that contains the cursor
    val chunkIndex = chunkStarts.indexWhere(_ > c) match {
      case -1 => chunks.length-1
      case x => x - 1
    }
    (chunks, chunkStarts, chunkIndex)
  }

  def moveEnd(b: Vector[Char],
                   c: Int,
                   w: Int) = {
    val (chunks, chunkStarts, chunkIndex) = findChunks(b, c)
    val currentColumn = (c - chunkStarts(chunkIndex)) % w
    chunks.lift(chunkIndex + 1) match{
      case Some(next) =>
        val boundary = chunkStarts(chunkIndex + 1) - 1
        if ((boundary - c) > (w - currentColumn)) {
          val delta= w - currentColumn
          c + delta
        }
        else boundary
      case None =>
        c + 1 * 9999
    }

  }
  def moveStart(b: Vector[Char],
                   c: Int,
                   w: Int) = {
    val (chunks, chunkStarts, chunkIndex) = findChunks(b, c)
    val currentColumn = (c - chunkStarts(chunkIndex)) % w
    c - currentColumn

  }

  def moveUpDown(b: Vector[Char],
                 c: Int,
                 w: Int,
                 boundaryOffset: Int,
                 nextChunkOffset: Int,
                 checkRes: Int,
                 check: (Int, Int) => Boolean,
                 isDown: Boolean) = {
    val (chunks, chunkStarts, chunkIndex) = findChunks(b, c)
    val offset = chunkStarts(chunkIndex + boundaryOffset)
    if (check(checkRes, offset)) checkRes
    else chunks.lift(chunkIndex + nextChunkOffset) match{
      case None => c + nextChunkOffset * 9999
      case Some(next) =>
      val boundary = chunkStarts(chunkIndex + boundaryOffset)
      val currentColumn = (c - chunkStarts(chunkIndex)) % w

      if (isDown) boundary + math.min(currentColumn, next)
      else boundary + math.min(currentColumn - next % w, 0) - 1
    }
  }
  def moveUp(b: Vector[Char], c: Int, w: Int) = {
    moveUpDown(b, c, w, 0, -1, c - w, _ > _, false)
  }
  def moveDown(b: Vector[Char], c: Int, w: Int) = {
    moveUpDown(b, c, w, 1, 1, c + w, _ <= _, true)
  }

  lazy val basicNavFilter : TermCore.Filter = {
    case TI(TS(p"\u001b[A$rest", b, c), w) => Debug("up"); TS(rest, b, moveUp(b, c, w))
    case TI(TS(p"\u001b[B$rest", b, c), w) => Debug("down"); TS(rest, b, moveDown(b, c, w))
    case TS(p"\u001b[C$rest", b, c) => Debug("right"); TS(rest, b, c + 1)
    case TS(p"\u001b[D$rest", b, c) => Debug("left"); TS(rest, b, c - 1)
    case TS(p"\u001b[5~$rest", b, c) => Debug("fn-up"); TS(rest, b, c - 9999)
    case TS(p"\u001b[6~$rest", b, c) => Debug("fn-down"); TS(rest, b, c + 9999)
    case TI(TS(p"\u001b[F$rest", b, c), w) => Debug("fn-right"); TS(rest, b, moveEnd(b, c, w))
    case TI(TS(p"\u001b[H$rest", b, c), w) => Debug("fn-left"); TS(rest, b, moveStart(b, c, w))

  }
  lazy val advancedNavFilter: TermCore.Filter = {
    case TS(p"\u001b\u001b[A$rest", b, c) => Debug("alt-up"); TS(rest, b, c)
    case TS(p"\u001b\u001b[B$rest", b, c) => Debug("alt-down"); TS(rest, b, c)
    case TS(p"\u001b\u001b[C$rest", b, c) => Debug("alt-right"); TS(rest, b, c)
    case TS(p"\u001b\u001b[D$rest", b, c) => Debug("alt-left"); TS(rest, b, c)

    case TS(p"\u001b[1;2A$rest", b, c) => Debug("shift-up"); TS(rest, b, c)
    case TS(p"\u001b[1;2B$rest", b, c) => Debug("shift-down"); TS(rest, b, c)
    case TS(p"\u001b[1;2C$rest", b, c) => Debug("shift-right"); TS(rest, b, c)
    case TS(p"\u001b[1;2D$rest", b, c) => Debug("shift-left"); TS(rest, b, c)

    case TS(p"\u001b\u001b[5~$rest", b, c) => Debug("fn-alt-up"); TS(rest, b, c)
    case TS(p"\u001b\u001b[6~$rest", b, c) => Debug("fn-alt-down"); TS(rest, b, c)
    case TS(p"\u001b[1;9F$rest", b, c) => Debug("fn-alt-right"); TS(rest, b, c)
    case TS(p"\u001b[1;9H$rest", b, c) => Debug("fn-alt-left"); TS(rest, b, c)

    // Conflicts with iTerm hotkeys, same as fn-{up, down}
    // case TS(pref"\u001b[5~$rest", b, c) => TS(rest, b, c) //fn-shift-up
    // case TS(pref"\u001b[6~$rest", b, c) => TS(rest, b, c) //fn-shift-down
    case TS(p"\u001b[1;2F$rest", b, c) => Debug("fn-shift-right"); TS(rest, b, c)
    case TS(p"\u001b[1;2H$rest", b, c) => Debug("fn-shift-left"); TS(rest, b, c)

    case TS(p"\u001b[1;10A$rest", b, c) => Debug("alt-shift-up"); TS(rest, b, c)
    case TS(p"\u001b[1;10B$rest", b, c) => Debug("alt-shift-down"); TS(rest, b, c)
    case TS(p"\u001b[1;10C$rest", b, c) => Debug("alt-shift-right"); TS(rest, b, c)
    case TS(p"\u001b[1;10D$rest", b, c) => Debug("alt-shift-left"); TS(rest, b, c)

    // Same as the case fn-alt-{up,down} without the shift
    // case TS(pref"\u001b\u001b[5~$rest", b, c) => TS(rest, b, c) //fn-alt-shift-up
    // case TS(pref"\u001b\u001b[6~$rest", b, c) => TS(rest, b, c) //fn-alt-shift-down
    case TS(p"\u001b[1;10F$rest", b, c) => Debug("fn-alt-shift-right"); TS(rest, b, c)
    case TS(p"\u001b[1;10H$rest", b, c) => Debug("fn-alt-shift-left"); TS(rest, b, c)
  }
}

