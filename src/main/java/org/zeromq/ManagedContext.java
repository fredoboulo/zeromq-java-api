package org.zeromq;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.zeromq.ZMQ.Socket;

// This is to avoid people trying to initialize a Context
class ManagedContext
{
    static {
        // Release ManagedSocket resources when catching SIGINT
        Runtime.getRuntime().addShutdownHook(new Thread()
        {
            @Override
            public void run()
            {
                getInstance().close();
            }
        });
    }

    private final Lock        lock;
    private final ZContext    ctx;
    private final Set<Socket> sockets;

    private ManagedContext()
    {
        this.ctx = new ZContext();
        this.lock = new ReentrantLock();
        this.sockets = new HashSet<>();
    }

    static ManagedContext getInstance()
    {
        return ContextHolder.INSTANCE;
    }

    Socket createSocket(int type)
    {
        final Socket base = ctx.createSocket(type);
        lock.lock();
        try {
            sockets.add(base);
        }
        finally {
            lock.unlock();
        }
        return base;
    }

    void destroy(Socket socketBase)
    {
        try {
            socketBase.setLinger(0);
            socketBase.close();
        }
        catch (Exception e) {
        }
        lock.lock();
        try {
            sockets.remove(socketBase);
        }
        finally {
            lock.unlock();
        }
    }

    /*
     * This should only be called when SIGINT is received
     */
    private void close()
    {
        lock.lock();
        try {
            for (Socket s : sockets) {
                try {
                    s.setLinger(0);
                    s.close();
                }
                catch (Exception ignore) {
                }
            }
            sockets.clear();
        }
        finally {
            lock.unlock();
        }
    }

    // Lazy singleton pattern to avoid double lock checking
    private static class ContextHolder
    {
        private static final ManagedContext INSTANCE = new ManagedContext();
    }
}
