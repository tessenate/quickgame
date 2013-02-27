package models

import scala.util.{Try, Success, Failure}

import akka.actor._
import scala.concurrent.duration._

import play.api._
import play.api.libs.json._
import play.api.libs.iteratee._
import play.api.libs.concurrent._

import akka.util.Timeout
import akka.pattern.ask

import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits._

object TicTacToeRoom {

  type Pos = (Int, Int)
  type Player = Int
  type Board = Map[Pos, Player]
  
  
  def nextPlayer(p: Player): Player = 1 - p
  def outOfBounds(pos: Pos) = {
    val (i, j) = pos
    i < 0 || i >= 3 || j < 0 || j >= 3
  }

  def winningMove(board: Board, move: Pos, player: Player): Boolean = {
    val (row, col) = move
    (0 until 3).forall(board(_, col) == player) ||
      (0 until 3).forall(board(row, _) == player) ||
      (0 until 3).forall(k => board(k, k) == player) ||
      (0 until 3).forall(k => board(k, 2-k) == player)
  }

  trait State {
    def board: Board
    def move(player: Player, pos: Pos): Try[State] = this match {
      case turn @ Turn(board, currentPlayer) => Try {
        require(player == currentPlayer, "Wrong player.")
        require(!board.contains(pos), "Invalid board position.")
        require(!outOfBounds(pos), "Position out of bounds.")
        val newBoard = board updated (pos, currentPlayer)
        if (winningMove(newBoard, pos, currentPlayer)) {
          Win(newBoard, currentPlayer)
        } else if (newBoard.size == 3 * 3) {
          Draw(newBoard)
        } else {
          turn.copy(board = newBoard, currentPlayer = nextPlayer(currentPlayer))
        }
      }
      case _ => Failure(new Exception("The game is completed."))
    }
  }

  case class Turn(board: Board, currentPlayer: Player) extends State
  case class Win(board: Board, player: Player) extends State
  case class Draw(board: Board) extends State

  case class Move(username: String, move: (Int,Int))
}

import TicTacToeRoom._

class TicTacToeRoom extends Actor {
  var gameState: State = Turn(Map.empty withDefaultValue -1, 0)
  var players = Map[String,Int]()
  var currentNumPlayers = 0
  val (chatEnumerator, chatChannel) = Concurrent.broadcast[JsValue]

  def iteratee(username: String): Iteratee[JsValue, _] =
    Iteratee.foreach[JsValue] { event => 
      Logger.debug(event.toString)
      (event \ "kind").as[String] match {
        case "talk" => self ! Talk(username, (event \ "text").as[String])
        case "turn" => {
          Logger.debug((event\"row").as[Int] + " " + (event\"col").as[Int])
          self ! Move(username, ((event \ "row").as[Int], (event \ "col").as[Int]))
        }
      }
    } mapDone { _ =>
      self ! Quit(username)
    }

  def sendState(state: State) {
    val (stateString, player) = state match {
      case Turn(_, p) => ("turn", p)
      case Win(_, p) => ("win", p)
      case Draw(_) => ("draw", -1)
    }
    val jsonBoard = JsArray(for (i <- 0 until 3) yield {
      JsArray(for (j <- 0 until 3) yield {
        JsNumber(BigDecimal(state.board.get((i, j)).getOrElse(-1)))
      })
    })
    val msg = Json.obj(
      "kind" -> stateString,
      "player" -> player,
      "board" -> jsonBoard
    )
    Logger.debug(msg.toString)
    chatChannel.push(msg)


  }

  override def receive = {
    case Join(username) =>
      if (players contains username) {
        sender ! CannotConnect("This username is already used")
      } else if (currentNumPlayers >= 2) {
        sender ! CannotConnect("Too many players!")
      } else {
        players += (username -> currentNumPlayers)
        currentNumPlayers += 1
        sender ! Connected(iteratee(username), chatEnumerator)
      }

    case Quit(username) =>
      if (players contains username) {
       players -= username 
      }

    case Move(username, move) =>
      Logger.debug(username + " " + move)
      players.get(username) map { playerNumber =>
        gameState.move(playerNumber, move) match {
          case Failure(e) => {
            Logger.debug(s"Bad move $move made by $username: $e")
          }
          case Success(newState) => {
            Logger.debug(s"Valid move $move made by $username")
            gameState = newState
            sendState(gameState)
          }
        }
      } getOrElse {
        Logger.debug(s"Unknown player $username")
      }
  }
}

