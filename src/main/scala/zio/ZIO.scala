package zio

import zio.examples.Person

import scala.concurrent.ExecutionContext
import zio.ZIO.Succeed
import zio.ZIO.Effect
import zio.ZIO.Async
import zio.ZIO.Fork

import java.util.concurrent.atomic.AtomicReference

// Declarative encoding CHECK
// Stack safety CHECK
// Concurrency safety CHECK
// Custom ExecutionContext
// Interruption
// Error handling
// Environment

trait Fiber[+A] {
  def start: Unit

  def join: ZIO[A]
}

class FiberImpl[A](zio: ZIO[A]) extends Fiber[A] {

  sealed trait FiberState

  case class Running(callbacks: List[A => Any]) extends FiberState
  case class Done(result: A)                    extends FiberState

  val state: AtomicReference[FiberState] =
    new AtomicReference(Running(List.empty))

  def complete(result: A): Unit = {
    var loop = true
    while (loop) {
      val oldState = state.get
      oldState match {
        case Running(callbacks) =>
          if (state.compareAndSet(oldState, Done(result))) {
            callbacks.foreach(cb => cb(result))
            loop = false
          }
        case Done(result) =>
          throw new Exception("Internal defect: Fiber being completed multiple times")
      }
    }
  }

  def await(callback: A => Any): Unit = {
    var loop = true
    while (loop) {
      val oldState = state.get
      oldState match {
        case Running(callbacks) =>
          val newState = Running(callback :: callbacks)
          loop = !state.compareAndSet(oldState, newState)
        case Done(result) =>
          callback(result)
          loop = false
      }
    }
  }

  // === CAS ===
  // compare and swap

  var i = 0

  // Expectations in synchronous world

  // Thread #1
  // get the old value of 0
  // do some operation on it (0 + 1)
  // set it to the new value only if equal to old value (1)
  // otherwise go back to first step

  // Thread #2
  // get the old value of 1
  // do some operation on it (1 + 1)
  // set it to the new value (2)

  // One possible order of execution

  // === ATOM ===
  // late 15th century: from Old French atome,
  // via Latin from Greek atomos ‘indivisible’,
  // based on a- ‘not’ + temnein ‘to cut’.
  // "Atom" Fraser

  // T1: get the old value of 0
  // T1: do some operation on it (0 + 1)
  // T2: get the old value of 0
  // T1: set it to the new value (1) if it is 0 (what we got above)
  // T2: do some operation on it (0 + 1)
  // T2: set it to the new value (1) if it is 0 (what we got above) — NO IT WAS 1! HELP! 🍜😭 OH NO!

  // for {
  //   _ <- ZIO.succeed(i += 1).fork.repeat(10000)
  //   value <- ZIO.succeed(i)
  // } yield value

  var maybeResult: Option[A] = None
  var callbacks              = List.empty[A => Any]

  override def start: Unit =
    ExecutionContext.global.execute { () =>
      zio.run(complete)
    }

  override def join: ZIO[A] =
    ZIO.async { callback =>
      await(callback)
    }

}

sealed trait ZIO[+A] { self =>

  def fork: ZIO[Fiber[A]] =
    ZIO.Fork(self)

  def as[B](value: => B): ZIO[B] =
    self.map(_ => value)

  def flatMap[B](f: A => ZIO[B]): ZIO[B] =
    ZIO.FlatMap(self, f)

  def map[B](f: A => B): ZIO[B] =
    flatMap(f andThen ZIO.succeedNow)
//    flatMap { a => ZIO.succeedNow(f(a)) }
//    ZIO.Map(self, f)

  def repeat(n: Int): ZIO[Unit] =
    if (n <= 0) ZIO.succeedNow()
    else self *> repeat(n - 1)

  def zipPar[B](that: ZIO[B]): ZIO[(A, B)] =
    for {
      f1 <- self.fork
      b  <- that
      a  <- f1.join
    } yield (a, b)

  def zip[B](that: ZIO[B]): ZIO[(A, B)] =
    zipWith(that)(_ -> _)

  def *>[B](that: => ZIO[B]): ZIO[B] =
    self zipRight that

  def zipRight[B](that: => ZIO[B]): ZIO[B] =
    zipWith(that)((_, b) => b)

  def zipWith[B, C](that: => ZIO[B])(f: (A, B) => C): ZIO[C] =
    for {
      a <- self
      b <- that
    } yield f(a, b)

  final def run(callback: A => Unit): Unit = {

    type Erased         = ZIO[Any]
    type ErasedCallback = Any => Any
    type Cont           = Any => Erased

    def erase[A](zio: ZIO[A]): Erased =
      zio

    def eraseCallback[A](cb: A => Unit): ErasedCallback =
      cb.asInstanceOf[ErasedCallback]

    val stack = new scala.collection.mutable.Stack[Cont]()

    var currentZIO = erase(self)

    var loop = true

    def resume(): Unit = {
      loop = true
      run()
    }

    def complete(value: Any): Unit =
      if (stack.isEmpty) {
        loop = false
        callback(value.asInstanceOf[A])
      } else {
        val cont = stack.pop()
        currentZIO = cont(value)
      }

    def run(): Unit =
      while (loop)
        currentZIO match {
          case ZIO.Succeed(value) =>
            complete(value)

          case ZIO.Effect(thunk) =>
            complete(thunk())

          case ZIO.FlatMap(zio, cont) =>
            stack.push(cont)
            currentZIO = zio

          case ZIO.Async(register) =>
            if (stack.isEmpty) {
              loop = false
              register(eraseCallback(callback))
            } else {
              loop = false
              register { a =>
                currentZIO = ZIO.succeedNow(a)
                resume()
              }
            }

          case ZIO.Fork(zio) =>
            val fiber = new FiberImpl(zio)
            fiber.start
            complete(fiber)
        }

    run()
  }

}

object ZIO {
  def async[A](register: (A => Any) => Any): ZIO[A] =
    ZIO.Async(register)

  def succeed[A](value: => A): ZIO[A] =
    ZIO.Effect(() => value)

  def succeedNow[A](value: A): ZIO[A] = ZIO.Succeed(value)

  case class Succeed[A](value: A) extends ZIO[A]

  case class Effect[A](f: () => A) extends ZIO[A]

  case class FlatMap[A, B](zio: ZIO[A], f: A => ZIO[B]) extends ZIO[B]

  case class Async[A](register: (A => Any) => Any) extends ZIO[A]

  case class Fork[A](zio: ZIO[A]) extends ZIO[Fiber[A]]
}
