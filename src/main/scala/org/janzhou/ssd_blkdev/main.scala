package org.janzhou.ssd_blkdev

import com.sun.jna._
import org.janzhou.ftl._
import org.janzhou.native._
import org.janzhou.cminer._
import java.util.Calendar
import java.text.SimpleDateFormat

object main {
  class GCThread(ftl:FTL) extends Thread {
    var _heartbeat = 0

    def heartbeat = {
      _heartbeat = 0
    }

    var _stop = false
    def stopGC = {
      _stop = true
    }

    override def run() {
      while ( !_stop ) {
        this.synchronized {
          _heartbeat += 1
          if ( _heartbeat >= 1024 ) {
            println("background gc")
            if (!ftl.gc) {
              _heartbeat = 0
            }
          }
        }

        if ( _heartbeat < 1024 ) {
          Thread.sleep( 1000 )
        }
      }
    }
  }

  def main (args: Array[String]) {
    val fd = libc.run.open("/dev/ssd_ramdisk", libc.O_RDWR)

    if (fd < 0) {
      println("Failed to open the device node");
      return
    }

    libc.run.ioctl(fd, 0x7800) //SSD_BLKDEV_REGISTER_APP
    println("Successfully registered the application with SSD RAMDISK driver.")

    val req_size = libc.run().malloc(8)

    val device = if ( args.length >= 2 ) {
      new Device(fd, args(1))
    } else {
      new Device(fd)
    }
    val ftl:FTL = if( args.isEmpty ) {
      new DirectFTL(device)
    } else {
      args(0) match {
        case "DirectFTL" => new DirectFTL(device)
        case "DFTL" => new DFTL(device)
        case "dftl" => new DFTL(device)
        case "CPFTL" => new CPFTL(device)
        case "cpftl" => new CPFTL(device)
        case "LSHFTL" => new CPFTL(device, new LSHMiner())
        case "lshftl" => new CPFTL(device, new LSHMiner())
        case _ => new DirectFTL(device)
      }
    }

    val time = new SimpleDateFormat("HH:mm:ss")

    val gc_thread = new GCThread(ftl)
    gc_thread.start()

    sys.addShutdownHook({
      gc_thread.stopGC
      gc_thread.join()
      libc.run().close(fd);
    })

    while (true) {
      libc.run.ioctl(fd, 0x80087801, req_size) //SSD_BLKDEV_GET_REQ_SIZE

      val request_map = libc.run().calloc(req_size.getInt(0), 88) //sizeof(*request_map)
      libc.run.ioctl(fd, 0x80087802, request_map) //SSD_BLKDEV_GET_LBN

      gc_thread.synchronized {
        for( i <- 0 to req_size.getInt(0) - 1 ) {
          val offset = i * 88
          val dir = request_map.getInt(offset + 0)
          val num_sectors = request_map.getInt(offset + 4)
          val start_lba = request_map.getInt(offset + 8)
          val psn_offset = offset + 16

          var last_lpn = -1
          var last_ppn = -1

          for( i <- 0 to num_sectors - 1 ) {
            val sector = start_lba + i

            val lpn = sector / device.SectorsPerPage
            val sector_offset = sector % device.SectorsPerPage

            //println("dir " + dir + " sector " + sector + " lpn " + lpn + " offset " + sector_offset)

            val ppn = if(lpn == last_lpn) {
              last_ppn
            } else {
              if( dir == 0 ) {
                val ppn = ftl.read(lpn)
                //println(time.format(Calendar.getInstance().getTime()) + " R " + lpn + " " + ppn)
                ppn
              } else {
                val ppn = if ( sector_offset != 0 || num_sectors - i < device.SectorsPerPage ) {
                  //println("partial write lpn " + lpn + " sector " + sector + " offset " + sector_offset + " start_lba " + start_lba + " num_sectors " + num_sectors)
                  val old_ppn = ftl.read(lpn)
                  val new_ppn = ftl.write(lpn)
                  if( old_ppn < device.TotalPages ) device.move(old_ppn, new_ppn)
                  new_ppn
                } else {
                  ftl.write(lpn)
                }
                //println(time.format(Calendar.getInstance().getTime()) + " W " + lpn + " " + ppn)
                ppn
              }
            }

            last_lpn = lpn
            last_ppn = ppn

            request_map.setLong(psn_offset + i * 8, ppn * device.SectorsPerPage + sector_offset)
          }
        }
        gc_thread.heartbeat
      }

      libc.run.ioctl(fd, 0x40087803, request_map) //SSD_BLKDEV_GET_LBN
      libc.run.free(request_map);
    }

    libc.run().close(fd);
  }
}
