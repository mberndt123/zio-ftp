/*
 * Copyright 2017-2020 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package zio.ftp

import java.io.IOException
import org.apache.commons.net.ftp.{ FTP, FTPClient => JFTPClient, FTPSClient => JFTPSClient }
import zio.ftp.UnsecureFtp.Client
import zio.stream.ZStream
import zio.{ Scope, ZIO }
import zio.ZIO.{ acquireRelease, attemptBlockingIO }
import org.apache.commons.net.ftp.FTPReply

sealed private trait CommandCompletionState

object CommandCompletionState {
  case class Pending(path: String, finalized: Boolean) extends CommandCompletionState
  case object NotPending                               extends CommandCompletionState
}

/**
 * Unsecure Ftp client wrapper
 *
 * All ftp methods exposed are lift into ZIO or ZStream
 * The underlying java client only provide blocking methods.
 */
final private class UnsecureFtp(unsafeClient: Client) extends FtpAccessors[Client] {

  var commandCompletionPending: CommandCompletionState = CommandCompletionState.NotPending

  def stat(path: String): ZIO[Any, IOException, Option[FtpResource]] =
    execute(c => Option(c.mlistFile(path))).map(_.map(FtpResource.fromFtpFile(_)))

  def readFile(path: String, chunkSize: Int = 2048): ZStream[Any, IOException, Byte] =
    ZStream.unwrap {
      execute { c =>
        val r = Option(c.retrieveFileStream(path))
        if (FTPReply.isPositivePreliminary(c.getReplyCode())) {
          commandCompletionPending = CommandCompletionState.Pending(path, false)
          ZStream
            .fromInputStreamZIO(
              ZIO
                .fromOption(r)
                .mapError { _ =>
                  new Exception("FTP client reported preliminary success but returned null. This shouldn't happen...")
                }
                .orDie,
              chunkSize
            )
            .ensuring {
              ZIO.succeed {
                commandCompletionPending = CommandCompletionState.Pending(path, true)
              }
            }
        } else {
          val msg = c.getReplyString().trim
          ZStream.fail(new IOException(msg))
        }
      }
    }

  def rm(path: String): ZIO[Any, IOException, Unit] =
    execute(_.deleteFile(path))
      .filterOrFail(identity)(InvalidPathError(s"Path is invalid. Cannot delete file : $path"))
      .unit

  def rmdir(path: String): ZIO[Any, IOException, Unit] =
    execute(_.removeDirectory(path))
      .filterOrFail(identity)(InvalidPathError(s"Path is invalid. Cannot delete directory : $path"))
      .unit

  def mkdir(path: String): ZIO[Any, IOException, Unit] =
    execute(_.makeDirectory(path))
      .filterOrFail(identity)(InvalidPathError(s"Path is invalid. Cannot create directory : $path"))
      .unit

  def ls(path: String): ZStream[Any, IOException, FtpResource] =
    ZStream
      .fromZIO(execute(_.listFiles(path).toList))
      .flatMap(ZStream.fromIterable(_))
      .map(FtpResource.fromFtpFile(_, Some(path)))

  def lsDescendant(path: String): ZStream[Any, IOException, FtpResource] =
    ZStream
      .fromZIO(execute(_.listFiles(path).toList))
      .flatMap(ZStream.fromIterable(_))
      .flatMap { f =>
        if (f.isDirectory) {
          val dirPath = Option(path).filter(_.endsWith("/")).fold(s"$path/${f.getName}")(p => s"$p${f.getName}")
          lsDescendant(dirPath)
        } else
          ZStream(FtpResource.fromFtpFile(f, Some(path)))
      }

  def upload[R](path: String, source: ZStream[R, Throwable, Byte]): ZIO[R, IOException, Unit] =
    ZIO.scoped[R] {
      source.toInputStream
        .mapError(new IOException(_))
        .flatMap(is =>
          execute(_.storeFile(path, is))
            .filterOrFail(identity)(InvalidPathError(s"Path is invalid. Cannot upload data to : $path"))
            .unit
        )
    }

  def rename(oldPath: String, newPath: String): ZIO[Any, IOException, Unit] =
    execute(_.rename(oldPath, newPath))
      .filterOrFail(identity)(InvalidPathError(s"Path is invalid. Cannot rename $oldPath to $newPath"))
      .unit

  override def execute[T](f: Client => T): ZIO[Any, IOException, T] = {
    val doExecute = attemptBlockingIO(f(unsafeClient))
    ZIO.succeed(commandCompletionPending).flatMap {
      case CommandCompletionState.Pending(path, false) =>
        ZIO.die(new Exception("bad zio-ftp client state: must call completePendingCommand first!"))
      case CommandCompletionState.Pending(path, true)  =>
        attemptBlockingIO(unsafeClient.completePendingCommand()).flatMap {
          if (_)
            ZIO.succeed {
              commandCompletionPending = CommandCompletionState.NotPending
            } *> doExecute
          else
            ZIO.fail(
              FileTransferIncompleteError(
                s"Cannot finalize the file transfer and completely read the entire file $path."
              )
            )
        }
      case CommandCompletionState.NotPending           =>
        doExecute
    }
  }
}

object UnsecureFtp {
  type Client = JFTPClient

  def connect(settings: UnsecureFtpSettings): ZIO[Scope, ConnectionError, FtpAccessors[Client]] =
    acquireRelease(
      attemptBlockingIO {
        val ftpClient = settings.sslParams.fold(new JFTPClient()) { ssl =>
          new JFTPSClient(ssl.isImplicit)
        }

        settings.controlEncoding match {
          case Some(enc) => ftpClient.setControlEncoding(enc)
          case None      => ftpClient.setAutodetectUTF8(true)
        }

        settings.proxy.foreach(ftpClient.setProxy)
        ftpClient.connect(settings.host, settings.port)

        val success = ftpClient.login(settings.credentials.username, settings.credentials.password)

        if (settings.binary)
          ftpClient.setFileType(FTP.BINARY_FILE_TYPE)

        if (settings.passiveMode)
          ftpClient.enterLocalPassiveMode()

        if (settings.remoteVerificationEnabled)
          ftpClient.setRemoteVerificationEnabled(settings.remoteVerificationEnabled)

        //https://enterprisedt.com/products/edtftpjssl/doc/manual/html/ftpscommands.html
        (ftpClient, settings.sslParams) match {
          case (c: JFTPSClient, Some(ssl)) =>
            c.execPBSZ(ssl.pbzs)
            c.execPROT(ssl.prot.s)
          case _                           => ()
        }

        settings.dataTimeout.foreach(ftpClient.setDataTimeout)

        new UnsecureFtp(ftpClient) -> success
      }.mapError(e => ConnectionError(e.getMessage, e))
        .filterOrFail(_._2)(ConnectionError(s"Fail to connect to server ${settings.host}:${settings.port}"))
        .map(_._1)
    )(client => client.execute(_.logout()).ignore *> client.execute(_.disconnect()).ignore)

}
