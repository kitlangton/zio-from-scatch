package zio

import zio.examples.Person

import scala.concurrent.ExecutionContext
import zio.ZIO.SucceedNow
import zio.ZIO.Succeed
import zio.ZIO.Async
import zio.ZIO.Fork

import java.util.concurrent.atomic.AtomicReference

// Declarative encoding CHECK
// Stack safety CHECK
// Concurrency safety CHECK
// Custom ExecutionContext CHECK
// Interruption
// Error handling
// Environment

trait Fiber[+E, +A] {

  def join: ZIO[E, A]

  def interrupt: ZIO[Nothing, Unit] =
    ???
}

private final case class FiberContext[E, A](startZIO: ZIO[E, A], startExecutor: ExecutionContext) extends Fiber[E, A] {

  sealed trait FiberState

  case class Running(callbacks: List[Either[E, A] => Any]) extends FiberState
  case class Done(result: Either[E, A])                    extends FiberState

  val state: AtomicReference[FiberState] =
    new AtomicReference(Running(List.empty))

  def complete(result: Either[E, A]): Unit = {
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

  def await(callback: Either[E, A] => Any): Unit = {
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

  override def join: ZIO[E, A] =
    ZIO
      .async[Either[E, A]] { callback =>
        await(callback)
      }
      .flatMap(ZIO.fromEither)

  type Erased         = ZIO[Any, Any]
  type ErasedCallback = Any => Any
  type Cont           = Any => Erased

  def erase[E, A](zio: ZIO[E, A]): Erased =
    zio

  def eraseCallback[A](cb: A => Unit): ErasedCallback =
    cb.asInstanceOf[ErasedCallback]

  val stack = new scala.collection.mutable.Stack[Cont]()

  var currentZIO      = erase(startZIO)
  var currentExecutor = startExecutor

  var loop = true

  def resume(): Unit = {
    loop = true
    run()
  }

  def continue(value: Any): Unit =
    if (stack.isEmpty) {
      loop = false
      complete(Right(value.asInstanceOf[A]))
    } else {
      val cont = stack.pop()
      currentZIO = cont(value)
    }

  def findNextErrorHandler(): ZIO.Fold[Any, Any, Any, Any] = {
    var loop                                       = true
    var errorHandler: ZIO.Fold[Any, Any, Any, Any] = null
    while (loop)
      if (stack.isEmpty) loop = false
      else {
        val cont = stack.pop()
        if (cont.isInstanceOf[ZIO.Fold[Any, Any, Any, Any]]) {
          errorHandler = cont.asInstanceOf[ZIO.Fold[Any, Any, Any, Any]]
          loop = false
        }
      }
    errorHandler
  }

  def run(): Unit =
    while (loop)
      currentZIO match {
        case ZIO.SucceedNow(value) =>
          continue(value)

        case ZIO.Succeed(thunk) =>
          continue(thunk())

        case ZIO.FlatMap(zio, cont) =>
          stack.push(cont)
          currentZIO = zio

        case ZIO.Async(register) =>
          if (stack.isEmpty) {
            loop = false
            register(a => complete(Right(a.asInstanceOf[A])))
          } else {
            loop = false
            register { a =>
              currentZIO = ZIO.succeedNow(a)
              resume()
            }
          }

        case ZIO.Fork(zio) =>
          val fiber = FiberContext(zio, currentExecutor)
          continue(fiber)

        case ZIO.Shift(executor) =>
          currentExecutor = executor
          continue(())

        case ZIO.Fail(e) =>
          val errorHandler = findNextErrorHandler()
          if (errorHandler eq null) {
            complete(Left(e().asInstanceOf[E]))
          } else {
            currentZIO = errorHandler.failure(e())
          }

        case fold @ ZIO.Fold(zio, failure, success) =>
          stack.push(fold)
          currentZIO = zio
      }

  currentExecutor.execute(() => run())
}

sealed trait ZIO[+E, +A] { self =>

  def fork: ZIO[Nothing, Fiber[E, A]] =
    ZIO.Fork(self)

  def as[B](value: => B): ZIO[E, B] =
    self.map(_ => value)

  def catchAll[E2, A1 >: A](f: E => ZIO[E2, A1]): ZIO[E2, A1] =
    foldZIO(e => f(e), a => ZIO.succeedNow(a))

  def flatMap[E1 >: E, B](f: A => ZIO[E1, B]): ZIO[E1, B] =
    ZIO.FlatMap(self, f)

  def fold[B](failure: E => B, success: A => B): ZIO[E, B] =
    foldZIO(e => ZIO.succeedNow(failure(e)), a => ZIO.succeedNow(success(a)))

  def foldZIO[E2, B](failure: E => ZIO[E2, B], success: A => ZIO[E2, B]): ZIO[E2, B] =
    ZIO.Fold(self, failure, success)

  def map[B](f: A => B): ZIO[E, B] =
    flatMap(f andThen ZIO.succeedNow)
//    flatMap { a => ZIO.succeedNow(f(a)) }
//    ZIO.Map(self, f)

  def repeat(n: Int): ZIO[E, Unit] =
    if (n <= 0) ZIO.succeedNow()
    else self *> repeat(n - 1)

  def shift(executor: ExecutionContext): ZIO[Nothing, Unit] =
    ZIO.Shift(executor)

  def zipPar[E1 >: E, B](that: ZIO[E1, B]): ZIO[E1, (A, B)] =
    for {
      f1 <- self.fork
      b  <- that
      a  <- f1.join
    } yield (a, b)

  def zip[E1 >: E, B](that: ZIO[E1, B]): ZIO[E1, (A, B)] =
    zipWith(that)(_ -> _)

  def *>[E1 >: E, B](that: => ZIO[E1, B]): ZIO[E1, B] =
    self zipRight that

  def zipRight[E1 >: E, B](that: => ZIO[E1, B]): ZIO[E1, B] =
    zipWith(that)((_, b) => b)

  def zipWith[E1 >: E, B, C](that: => ZIO[E1, B])(f: (A, B) => C): ZIO[E1, C] =
    for {
      a <- self
      b <- that
    } yield f(a, b)

  private final def unsafeRunFiber: Fiber[E, A] =
    FiberContext(self, ZIO.defaultExecutor)

  final def unsafeRunSync: Either[E, A] = {
    val latch                = new java.util.concurrent.CountDownLatch(1)
    var result: Either[E, A] = null.asInstanceOf[Either[E, A]]
    val zio = self.foldZIO(
      e =>
        ZIO.succeed {
          result = Left(e)
          latch.countDown()
        },
      a =>
        ZIO.succeed {
          result = Right(a)
          latch.countDown()
        }
    )
    zio.unsafeRunFiber
    latch.await()
    result
  }
}

object ZIO {
  def async[A](register: (A => Any) => Any): ZIO[Nothing, A] =
    ZIO.Async(register)

  def fail[E](e: => E): ZIO[E, Nothing] =
    Fail(() => e)

  def fromEither[E, A](either: Either[E, A]): ZIO[E, A] =
    either.fold(e => fail(e), a => succeedNow(a))

  def succeed[A](value: => A): ZIO[Nothing, A] =
    ZIO.Succeed(() => value)

  def succeedNow[A](value: A): ZIO[Nothing, A] = ZIO.SucceedNow(value)

  case class SucceedNow[A](value: A) extends ZIO[Nothing, A]

  case class Succeed[A](f: () => A) extends ZIO[Nothing, A]

  case class FlatMap[E, A, B](zio: ZIO[E, A], f: A => ZIO[E, B]) extends ZIO[E, B]

  case class Async[A](register: (A => Any) => Any) extends ZIO[Nothing, A]

  case class Fork[E, A](zio: ZIO[E, A]) extends ZIO[Nothing, Fiber[E, A]]

  case class Shift(executor: ExecutionContext) extends ZIO[Nothing, Unit]

  case class Fail[E](e: () => E) extends ZIO[E, Nothing]

  case class Fold[E, E2, A, B](zio: ZIO[E, A], failure: E => ZIO[E2, B], success: A => ZIO[E2, B])
      extends ZIO[E2, B]
      with (A => ZIO[E2, B]) {
    def apply(a: A): ZIO[E2, B] = success(a)
  }

  private val defaultExecutor = ExecutionContext.global
}
