/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.apache.drill.exec.rpc;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

import java.io.IOException;
import java.net.BindException;

import org.apache.drill.exec.exception.DrillbitStartupException;
import org.apache.drill.exec.proto.GeneralRPCProtos.RpcMode;

import com.google.protobuf.Internal.EnumLite;
import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;

/**
 * A server is bound to a port and is responsible for responding to various type of requests. In some cases, the inbound
 * requests will generate more than one outbound request.
 */
public abstract class BasicServer<T extends EnumLite, C extends RemoteConnection> extends RpcBus<T, C>{
  static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(BasicServer.class);

  private ServerBootstrap b;
  private volatile boolean connect = false;
  private final EventLoopGroup eventLoopGroup;

  public BasicServer(RpcConfig rpcMapping, ByteBufAllocator alloc, EventLoopGroup eventLoopGroup) {
    super(rpcMapping);
    this.eventLoopGroup = eventLoopGroup;
    
    b = new ServerBootstrap() //
        .channel(NioServerSocketChannel.class) //
        .option(ChannelOption.SO_BACKLOG, 100) //
        .option(ChannelOption.SO_RCVBUF, 1 << 17) //
        .option(ChannelOption.SO_SNDBUF, 1 << 17) //
        .group(eventLoopGroup) //
        .childOption(ChannelOption.ALLOCATOR, alloc) //
        .handler(new LoggingHandler(LogLevel.INFO)) //
        .childHandler(new ChannelInitializer<SocketChannel>() {
          @Override
          protected void initChannel(SocketChannel ch) throws Exception {
            
            C connection = initRemoteConnection(ch);
            ch.closeFuture().addListener(getCloseHandler(connection));

            ch.pipeline().addLast( //
                new ZeroCopyProtobufLengthDecoder(), //
                new RpcDecoder(rpcConfig.getName()), //
                new RpcEncoder(rpcConfig.getName()), //
                getHandshakeHandler(connection),
                new InboundHandler(connection), //
                new RpcExceptionHandler() //
                );            
            connect = true;
            
          }
        });
  }
 
  @Override
  public boolean isClient() {
    return false;
  }

  
  protected abstract ServerHandshakeHandler<?> getHandshakeHandler(C connection);

  protected static abstract class ServerHandshakeHandler<T extends MessageLite> extends AbstractHandshakeHandler<T> {

    public ServerHandshakeHandler(EnumLite handshakeType, Parser<T> parser) {
      super(handshakeType, parser);
    }

    @Override
    protected final void consumeHandshake(Channel c, T inbound) throws Exception {
      OutboundRpcMessage msg = new OutboundRpcMessage(RpcMode.RESPONSE, this.handshakeType, coordinationId, getHandshakeResponse(inbound));
      c.write(msg);
    }
    
    public abstract MessageLite getHandshakeResponse(T inbound) throws Exception;
    
  }
  
  
  public int bind(final int initialPort) throws InterruptedException, DrillbitStartupException{
    int port = initialPort-1;
    while (true) {
      try {
        b.bind(++port).sync();
        break;
      } catch (Exception e) {
        if (e instanceof BindException)
          continue;
        throw new DrillbitStartupException("Could not bind Drillbit", e);
      }
    }
    
    connect = !connect;
    logger.debug("Server started on port {} of type {} ", port, this.getClass().getSimpleName());
    return port;    
  }

  @Override
  public void close() throws IOException {
    eventLoopGroup.shutdownGracefully();
  }
  
  

}
