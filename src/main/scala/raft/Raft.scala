package raft

import scala.language.postfixOps
import akka.actor.{ Actor, ActorRef, FSM, LoggingFSM }
import scala.concurrent.duration._
import scala.concurrent.Promise
import math.random

/* types */
object Raft {
  type Term = Int
  type NodeId = ActorRef
}

/* messages */
sealed trait Message
case object Timeout extends Message
case object Heartbeat extends Message
case class Init(nodes: List[Raft.NodeId]) extends Message

sealed trait Request extends Message
case class RequestVote(term: Raft.Term, candidateId: Raft.NodeId,
  lastLogIndex: Int, lastLogTerm: Raft.Term) extends Request
case class AppendEntries(
  term: Raft.Term,
  leaderId: Raft.NodeId,
  prevLogIndex: Int,
  prevLogTerm: Raft.Term,
  entries: List[LogEntry],
  leaderCommit: Int) extends Request

sealed trait Vote extends Message
case class DenyVote(term: Raft.Term) extends Vote
case class GrantVote(term: Raft.Term) extends Vote

sealed trait AppendReply extends Message
case class AppendFailure(term: Raft.Term) extends AppendReply
case class AppendSuccess(term: Raft.Term, index: Int) extends AppendReply

case class ClientRequest(cid: Int, command: String)
case class ClientRef(sender: Raft.NodeId, cid: Int)

/* states */
sealed trait Role
case object Leader extends Role
case object Follower extends Role
case object Candidate extends Role
case object Initialise extends Role

/* Consensus module */
class Raft() extends Actor with LoggingFSM[Role, Meta] {
  override def logDepth = 12

  startWith(Initialise, Meta(List()))

  when(Initialise) {
    case Event(cluster: Init, _) => goto(Follower) using initialised(cluster)
  }

  when(Follower) {
    case Event(rpc: RequestVote, data) =>
      vote(rpc, data) match {
        case (msg: GrantVote, updData) =>
          resetTimer
          stay using (updData) replying (msg)
        case (msg: DenyVote, updData) =>
          stay using (updData) replying (msg)
      }
    case Event(rpc: AppendEntries, data) =>
      resetTimer
      val (msg, upd) = append(rpc, data)
      stay using upd replying msg
    case Event(Timeout, data) =>
      log.debug("\n == WAITED LONG ENOUGH, NO LEADER IN SIGHT")
      goto(Candidate) using preparedForCandidate(data)
  }

  when(Candidate) {
    // voting events   
    case Event(rpc: RequestVote, data) if (rpc.term == data.term.current) =>
      val (msg, upd) = grant(rpc, data)
      stay using (upd) replying msg
    case Event(GrantVote(term), data: Meta) =>
      data.votes = data.votes.gotVoteFrom(sender)

      if (data.votes.majority(data.nodes.length)) {
        log.debug("\n == GOT MAJORITY, BECOMING LEADER")
        goto(Leader) using preparedForLeader(data)
      } else stay using data

    case Event(DenyVote(term), data: Meta) =>
      if (term > data.term.current)
        goto(Follower) using preparedForFollower(data)
      else stay

    // other   
    case Event(rpc: AppendEntries, data: Meta) =>
      log.debug("\n STEPPING DOWN for " + sender)
      goto(Follower) using preparedForFollower(data)
    case Event(Timeout, data: Meta) =>
      log.debug("\n == WAITED LONG ENOUGH, STILL NO LEADER")
      goto(Candidate) using preparedForCandidate(data)
  }

  when(Leader) {
    case Event(clientRpc: ClientRequest, data: Meta) =>
      writeToLog(sender, clientRpc, data)
      sendEntries(data)
      stay using data
    case Event(rpc: AppendSuccess, data: Meta) =>
      data.log = data.log.resetNextFor(sender)
      data.log = data.log.matchFor(sender, Some(rpc.index))
      commitEntries(rpc, data)
      applyEntries(data)
      stay
    case Event(rpc: AppendFailure, data: Meta) =>
      if (rpc.term <= data.term.current) {
        data.log = data.log.decrementNextFor(sender)
        resendTo(sender, data)
        stay
      } else {
        data.term = Term(rpc.term)
        goto(Follower) using preparedForFollower(data)
      }
    case Event(Heartbeat, data: Meta) =>
      sendEntries(data)
      stay
  }

  whenUnhandled {
    case Event(_, _) => stay // drop event
  }

  onTransition {
    case Leader -> Follower =>
      cancelTimer("heartbeat")
      resetTimer
    case Candidate -> Follower => resetTimer
    case Initialise -> Follower => resetTimer
  }

  onTermination {
    case StopEvent(FSM.Normal, state, data) =>
      log.debug("\n == SHUTTING DOWN: NORMAL")
    case StopEvent(FSM.Shutdown, state, data) =>
      log.debug("\n == SHUTTING DOWN: SHUTDOWN")
    case StopEvent(FSM.Failure(cause), state, data) =>
      log.debug("\n == SHUTTING DOWN: FAILURE")
  }

  private def preparedForFollower(state: Meta): Meta = {
    state.votes = Votes()
    state
  }

  private def preparedForCandidate(state: Meta): Meta = {
    state.term = state.term.nextTerm
    state.nodes.map { t =>
      t ! RequestVote(
        term = state.term.current,
        candidateId = self,
        lastLogIndex = state.log.lastIndex,
        lastLogTerm = state.log.lastTerm)
      log.debug("\n == REQUESTING VOTE FROM " + t)
    }
    resetTimer
    state
  }

  private def preparedForLeader(state: Meta) = {
    sendEntries(state)
    resetHeartbeatTimer
    state
  }

  private def initialised(cluster: Init): Meta = Meta(cluster.nodes)

  private def resetHeartbeatTimer = {
    cancelTimer("heartbeat")
    val nextTimeout = (random * 100).toInt + 100
    setTimer("heartbeat", Heartbeat, nextTimeout millis, false)
  }

  private def resetTimer = {
    cancelTimer("timeout")
    val nextTimeout = (random * 200).toInt + 200
    setTimer("timeout", Timeout, nextTimeout millis, false) // TODO: should pick random time
  }

  initialize() // akka specific

  /*
   *  --- Internals ---
   */

  private def applyEntries(data: Meta) = {
    for (i <- data.log.lastApplied until data.log.commitIndex) {
      val entry = data.log.entries(i)

      val result = data.rsm.execute(Get) // TODO: make generic

      entry.sender match {
        case Some(ref) => ref.sender ! (ref.cid, result)
        case None => // ignore
      }

      data.log = data.log.applied
    }
  }

  private def commitEntries(rpc: AppendSuccess, data: Meta) = {
    if (rpc.index > data.log.commitIndex &&
      data.log.termOf(rpc.index) == data.term.current) {
      val matches = data.log.matchIndex.count(_._2 == rpc.index)
      if (matches >= Math.ceil(data.nodes.length / 2.0))
        data.log = data.log.commit(rpc.index)
    }
  }

  private def sendEntries(data: Meta) = {
    resetHeartbeatTimer
    data.nodes.filterNot(_ == self).map { node =>
      val message = compileMessage(node, data)
      node ! message
      log.debug("\n == SENT MESSAGE TO: " + node)
    }
  }

  private def resendTo(node: Raft.NodeId, data: Meta) = {
    val message = compileMessage(node, data)
    node ! message
  }

  private def compileMessage(node: ActorRef, data: Meta): AppendEntries = {
    val prevIndex = data.log.prevIndex(node)
    val prevTerm = data.log.termOf(prevIndex)
    val fromMissing = data.log.lastIndex - prevIndex
    AppendEntries(
      term = data.term.current,
      leaderId = self,
      prevLogIndex = prevIndex,
      prevLogTerm = prevTerm,
      entries = data.log.entries.takeRight(fromMissing),
      leaderCommit = data.log.commitIndex
    )
  }

  private def writeToLog(sender: Raft.NodeId, rpc: ClientRequest, data: Meta) = {
    val ref = ClientRef(sender, rpc.cid)
    val entry = LogEntry(rpc.command, data.term.current, Some(ref))
    data.log = data.log.append(List(entry))
  }

  /*
   * AppendEntries handling 
   */
  private def append(rpc: AppendEntries, data: Meta): (AppendReply, Meta) = {
    if (leaderIsBehind(rpc, data)) appendFail(data)
    else if (!hasMatchingLogEntryAtPrevPosition(rpc, data)) appendFail(data)
    else appendSuccess(rpc, data)
  }

  private def leaderIsBehind(rpc: AppendEntries, data: Meta): Boolean =
    rpc.term < data.term.current

  private def hasMatchingLogEntryAtPrevPosition(
    rpc: AppendEntries, data: Meta): Boolean =
    (data.log.entries.isDefinedAt(rpc.prevLogIndex) &&
      (data.log.entries(rpc.prevLogIndex).term == rpc.prevLogTerm))

  private def appendFail(data: Meta) =
    (AppendFailure(data.term.current), data)

  private def appendSuccess(rpc: AppendEntries, data: Meta) = {
    // if newer entries exist in log these are not committed and can 
    // safely be removed - should add check during exhaustive testing
    // to ensure property holds
    data.log = data.log.append(rpc.entries, Some(rpc.prevLogIndex + 1))
    data.term = Term.max(data.term, Term(rpc.term))
    (AppendSuccess(data.term.current, data.log.lastIndex), data)
  }

  /*
   * Determine whether to grant or deny vote
   */
  private def vote(rpc: RequestVote, data: Meta): (Vote, Meta) =
    if (alreadyVoted(data)) deny(rpc, data)
    else if (rpc.term < data.term.current) deny(rpc, data)
    else if (rpc.term == data.term.current)
      if (candidateLogTermIsBehind(rpc, data)) deny(rpc, data)
      else if (candidateLogTermIsEqualButHasShorterLog(rpc, data)) deny(rpc, data)
      else grant(rpc, data) // follower and candidate are equal, grant
    else grant(rpc, data) // candidate is ahead, grant

  private def deny(rpc: RequestVote, data: Meta) = {
    data.term = Term.max(data.term, Term(rpc.term))
    (DenyVote(data.term.current), data)
  }
  private def grant(rpc: RequestVote, data: Meta): (Vote, Meta) = {
    data.votes = data.votes.vote(rpc.candidateId)
    data.term = Term.max(data.term, Term(rpc.term))
    log.debug("\n == VOTING FOR " + rpc.candidateId)
    (GrantVote(data.term.current), data)
  }

  private def candidateLogTermIsBehind(rpc: RequestVote, data: Meta) =
    data.log.entries.last.term > rpc.lastLogTerm

  private def candidateLogTermIsEqualButHasShorterLog(rpc: RequestVote, data: Meta) =
    (data.log.entries.last.term == rpc.lastLogTerm) &&
      (data.log.entries.length - 1 > rpc.lastLogIndex)

  private def alreadyVoted(data: Meta): Boolean = data.votes.votedFor match {
    case Some(_) => true
    case None => false
  }
}
