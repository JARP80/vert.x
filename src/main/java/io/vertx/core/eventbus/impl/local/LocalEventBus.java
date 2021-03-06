/*
 * Copyright (c) 2011-2014 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *     The Eclipse Public License is available at
 *     http://www.eclipse.org/legal/epl-v10.html
 *
 *     The Apache License v2.0 is available at
 *     http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.core.eventbus.impl.local;

import io.vertx.core.*;
import io.vertx.core.eventbus.*;
import io.vertx.core.eventbus.impl.*;
import io.vertx.core.impl.VertxInternal;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.spi.metrics.EventBusMetrics;
import io.vertx.core.spi.metrics.MetricsProvider;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 *
 * @author <a href="http://tfox.org">Tim Fox</a>                                                                                        T
 */
public class LocalEventBus implements EventBus, MetricsProvider {

  private static final Logger log = LoggerFactory.getLogger(LocalEventBus.class);

  private final AtomicLong replySequence = new AtomicLong(0);
  protected final VertxInternal vertx;
  protected final EventBusMetrics metrics;
  protected final ConcurrentMap<String, Handlers> handlerMap = new ConcurrentHashMap<>();
  protected final CodecManager codecManager = new CodecManager();
  protected volatile boolean started;

  public LocalEventBus(VertxInternal vertx) {
    this.vertx = vertx;
    this.metrics = vertx.metricsSPI().createMetrics(this);
  }

  public synchronized void start(Handler<AsyncResult<Void>> completionHandler) {
    if (started) {
      throw new IllegalStateException("Already started");
    }
    started = true;
    completionHandler.handle(Future.succeededFuture());
  }

  @Override
  public EventBus send(String address, Object message) {
    return send(address, message, new DeliveryOptions(), null);
  }

  @Override
  public <T> EventBus send(String address, Object message, Handler<AsyncResult<Message<T>>> replyHandler) {
    return send(address, message, new DeliveryOptions(), replyHandler);
  }

  @Override
  public EventBus send(String address, Object message, DeliveryOptions options) {
    return send(address, message, options, null);
  }

  @Override
  public <T> EventBus send(String address, Object message, DeliveryOptions options, Handler<AsyncResult<Message<T>>> replyHandler) {
    sendOrPub(createMessage(true, address, options.getHeaders(), message, options.getCodecName()), options, replyHandler);
    return this;
  }

  @Override
  public <T> MessageProducer<T> sender(String address) {
    Objects.requireNonNull(address, "address");
    return new MessageProducerImpl<>(this, address, true, new DeliveryOptions());
  }

  @Override
  public <T> MessageProducer<T> sender(String address, DeliveryOptions options) {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(options, "options");
    return new MessageProducerImpl<>(this, address, true, options);
  }

  @Override
  public <T> MessageProducer<T> publisher(String address) {
    Objects.requireNonNull(address, "address");
    return new MessageProducerImpl<>(this, address, false, new DeliveryOptions());
  }

  @Override
  public <T> MessageProducer<T> publisher(String address, DeliveryOptions options) {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(options, "options");
    return new MessageProducerImpl<>(this, address, false, options);
  }

  @Override
  public EventBus publish(String address, Object message) {
    return publish(address, message, new DeliveryOptions());
  }

  @Override
  public EventBus publish(String address, Object message, DeliveryOptions options) {
    sendOrPub(createMessage(false, address, options.getHeaders(), message, options.getCodecName()), options, null);
    return this;
  }

  @Override
  public <T> MessageConsumer<T> consumer(String address) {
    checkStarted();
    Objects.requireNonNull(address, "address");
    return new HandlerRegistration<>(vertx, metrics, this, address, false, false, null, -1);
  }

  @Override
  public <T> MessageConsumer<T> consumer(String address, Handler<Message<T>> handler) {
    Objects.requireNonNull(handler, "handler");
    MessageConsumer<T> consumer = consumer(address);
    consumer.handler(handler);
    return consumer;
  }

  @Override
  public <T> MessageConsumer<T> localConsumer(String address) {
    checkStarted();
    Objects.requireNonNull(address, "address");
    return new HandlerRegistration<>(vertx, metrics, this, address, false, true, null, -1);
  }

  @Override
  public <T> MessageConsumer<T> localConsumer(String address, Handler<Message<T>> handler) {
    Objects.requireNonNull(handler, "handler");
    MessageConsumer<T> consumer = localConsumer(address);
    consumer.handler(handler);
    return consumer;
  }

  @Override
  public EventBus registerCodec(MessageCodec codec) {
    codecManager.registerCodec(codec);
    return this;
  }

  @Override
  public EventBus unregisterCodec(String name) {
    codecManager.unregisterCodec(name);
    return this;
  }

  @Override
  public <T> EventBus registerDefaultCodec(Class<T> clazz, MessageCodec<T, ?> codec) {
    codecManager.registerDefaultCodec(clazz, codec);
    return this;
  }

  @Override
  public EventBus unregisterDefaultCodec(Class clazz) {
    codecManager.unregisterDefaultCodec(clazz);
    return this;
  }

  @Override
  public void close(Handler<AsyncResult<Void>> completionHandler) {
    checkStarted();
    unregisterAll();
    if (metrics != null) {
      metrics.close();
    }
    if (completionHandler != null) {
      vertx.runOnContext(v -> completionHandler.handle(Future.succeededFuture()));
    }
  }

  @Override
  public boolean isMetricsEnabled() {
    return metrics != null && metrics.isEnabled();
  }

  @Override
  public EventBusMetrics<?> getMetrics() {
    return metrics;
  }

  public LocalMessage createMessage(boolean send, String address, MultiMap headers, Object body, String codecName) {
    Objects.requireNonNull(address, "no null address accepted");
    MessageCodec codec = codecManager.lookupCodec(body, codecName);
    @SuppressWarnings("unchecked")
    LocalMessage msg = new LocalMessage(address, null, headers, body, codec, send);
    return msg;
  }

  public <T> void addRegistration(String address, HandlerRegistration<T> registration,
                                  boolean replyHandler, boolean localOnly) {
    Objects.requireNonNull(registration.getHandler(), "handler");
    doAddRegistration(address, registration, replyHandler, localOnly);
    registration.setResult(Future.succeededFuture());
  }

  protected <T> HandlerRegistration<T> createReplyHandlerRegistration(LocalMessage message,
                                                                      DeliveryOptions options,
                                                                      Handler<AsyncResult<Message<T>>> replyHandler) {
    if (replyHandler != null) {
      long timeout = options.getSendTimeout();
      String replyAddress = generateReplyAddress();
      message.setReplyAddress(replyAddress);
      Handler<Message<T>> simpleReplyHandler = convertHandler(replyHandler);
      HandlerRegistration<T> registration =
        new HandlerRegistration<>(vertx, metrics, this, replyAddress, true, true, replyHandler, timeout);
      registration.handler(simpleReplyHandler);
      return registration;
    } else {
      return null;
    }
  }

  protected <T> boolean doAddRegistration(String address, HandlerRegistration<T> registration,
                                          boolean replyHandler, boolean localOnly) {
    Objects.requireNonNull(address, "address");

    Context context = Vertx.currentContext();
    boolean hasContext = context != null;
    if (!hasContext) {
      // Embedded
      context = vertx.getOrCreateContext();
    }

    boolean newAddress = false;

    HandlerHolder holder = new HandlerHolder<>(metrics, registration, replyHandler, localOnly, context);

    Handlers handlers = handlerMap.get(address);
    if (handlers == null) {
      handlers = new Handlers();
      Handlers prevHandlers = handlerMap.putIfAbsent(address, handlers);
      if (prevHandlers != null) {
        handlers = prevHandlers;
      }
      newAddress = true;
    }
    handlers.list.add(holder);

    if (hasContext) {
      HandlerEntry entry = new HandlerEntry<>(address, registration);
      context.addCloseHook(entry);
    }

    return newAddress;
  }

  public <T> void removeRegistration(String address, HandlerRegistration<T> handler, Handler<AsyncResult<Void>> completionHandler) {
    removeRegistration(address, handler);
    callCompletionHandlerAsync(completionHandler);
  }

  protected <T> HandlerHolder removeRegistration(String address, HandlerRegistration<T> handler) {
    Handlers handlers = handlerMap.get(address);
    HandlerHolder lastHolder = null;
    if (handlers != null) {
      synchronized (handlers) {
        int size = handlers.list.size();
        // Requires a list traversal. This is tricky to optimise since we can't use a set since
        // we need fast ordered traversal for the round robin
        for (int i = 0; i < size; i++) {
          HandlerHolder holder = handlers.list.get(i);
          if (holder.getHandler() == handler) {
            handlers.list.remove(i);
            holder.setRemoved();
            if (handlers.list.isEmpty()) {
              handlerMap.remove(address);
              lastHolder = holder;
            }
            holder.getContext().removeCloseHook(new HandlerEntry<>(address, holder.getHandler()));
            break;
          }
        }
      }
    }
    return lastHolder;
  }

  protected <T> void sendReply(LocalMessage message, DeliveryOptions options,
                               Handler<AsyncResult<Message<T>>> replyHandler) {
    if (message.address() == null) {
      throw new IllegalStateException("address not specified");
    } else {
      sendOrPub(message, options, replyHandler);
    }
  }

  protected <T> void sendOrPub(LocalMessage message, DeliveryOptions options,
                               Handler<AsyncResult<Message<T>>> replyHandler) {
    checkStarted();
    HandlerRegistration<T> registration = createReplyHandlerRegistration(message, options, replyHandler);
    metrics.messageSent(message.address(), !message.send(), true, false);
    serveMessage(message, registration, true);
  }

  protected <T> Handler<Message<T>> convertHandler(Handler<AsyncResult<Message<T>>> handler) {
    return reply -> {
      Future<Message<T>> result;
      if (reply.body() instanceof ReplyException) {
        // This is kind of clunky - but hey-ho
        ReplyException exception = (ReplyException) reply.body();
        metrics.replyFailure(reply.address(), exception.failureType());
        result = Future.failedFuture(exception);
      } else {
        result = Future.succeededFuture(reply);
      }
      handler.handle(result);
    };
  }

  protected void callCompletionHandlerAsync(Handler<AsyncResult<Void>> completionHandler) {
    if (completionHandler != null) {
      vertx.runOnContext(v -> completionHandler.handle(Future.succeededFuture()));
    }
  }

  protected <T> void serveMessage(LocalMessage msg, HandlerRegistration<T> handlerRegistration, boolean local) {
    if (!serveMessage(msg, local)) {
      // no handlers
      if (handlerRegistration != null) {
        handlerRegistration.sendAsyncResultFailure(ReplyFailure.NO_HANDLERS, "No handlers for address " + msg.address);
      }
    }
  }

  protected <T> boolean serveMessage(LocalMessage msg, boolean local) {
    msg.setBus(this);
    Handlers handlers = handlerMap.get(msg.address());
    if (handlers != null) {
      if (msg.send()) {
        //Choose one
        HandlerHolder holder = handlers.choose();
        if (holder != null) {
          metrics.messageReceived(msg.address(), !msg.send(), local, 1);
          doServe(msg, holder);
        }
      } else {
        // Publish
        metrics.messageReceived(msg.address(), !msg.send(), local, handlers.list.size());
        for (HandlerHolder holder: handlers.list) {
          doServe(msg, holder);
        }
      }
      return true;
    } else {
      metrics.messageReceived(msg.address(), !msg.send(), local, 0);
      return false;
    }
  }

  protected void checkStarted() {
    if (!started) {
      throw new IllegalStateException("Event Bus is not started");
    }
  }

  protected String generateReplyAddress() {
    return Long.toString(replySequence.incrementAndGet());
  }

  private void unregisterAll() {
    // Unregister all handlers explicitly - don't rely on context hooks
    for (Handlers handlers: handlerMap.values()) {
      for (HandlerHolder holder: handlers.list) {
        holder.getHandler().unregister(true);
      }
    }
  }

  private <T> void doServe(LocalMessage msg, HandlerHolder<T> holder) {
    // Each handler gets a fresh copy
    @SuppressWarnings("unchecked")
    Message<T> copied = msg.copyBeforeReceive();

    holder.getContext().runOnContext((v) -> {
      // Need to check handler is still there - the handler might have been removed after the message were sent but
      // before it was received
      try {
        if (!holder.isRemoved()) {
          holder.getHandler().handle(copied);
        }
      } finally {
        if (holder.isReplyHandler()) {
          holder.getHandler().unregister();
        }
      }
    });
  }

  public class HandlerEntry<T> implements Closeable {
    final String address;
    final HandlerRegistration<T> handler;

    public HandlerEntry(String address, HandlerRegistration<T> handler) {
      this.address = address;
      this.handler = handler;
    }

    @Override
    public boolean equals(Object o) {
      if (o == null) return false;
      if (this == o) return true;
      if (getClass() != o.getClass()) return false;
      HandlerEntry entry = (HandlerEntry) o;
      if (!address.equals(entry.address)) return false;
      if (!handler.equals(entry.handler)) return false;
      return true;
    }

    @Override
    public int hashCode() {
      int result = address != null ? address.hashCode() : 0;
      result = 31 * result + (handler != null ? handler.hashCode() : 0);
      return result;
    }

    // Called by context on undeploy
    public void close(Handler<AsyncResult<Void>> completionHandler) {
      handler.unregister(completionHandler);
      completionHandler.handle(Future.succeededFuture());
    }

  }

  @Override
  protected void finalize() throws Throwable {
    // Make sure this gets cleaned up if there are no more references to it
    // so as not to leave connections and resources dangling until the system is shutdown
    // which could make the JVM run out of file handles.
    close(ar -> {});
    super.finalize();
  }

}

