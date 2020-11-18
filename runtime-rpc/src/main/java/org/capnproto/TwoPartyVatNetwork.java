package org.capnproto;

import java.nio.channels.AsynchronousByteChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class TwoPartyVatNetwork
        implements VatNetwork<RpcTwoPartyProtocol.VatId.Reader>,
                   VatNetwork.Connection<RpcTwoPartyProtocol.VatId.Reader> {

    private static final Logger LOGGER = Logger.getLogger(TwoPartyVatNetwork.class.getName());

    @Override
    public CompletableFuture<Connection<RpcTwoPartyProtocol.VatId.Reader>> baseAccept() {
        return this.accept();
    }

    private CompletableFuture<java.lang.Void> previousWrite = CompletableFuture.completedFuture(null);
    private final CompletableFuture<java.lang.Void> disconnectPromise = new CompletableFuture<>();
    private final AsynchronousByteChannel channel;
    private final RpcTwoPartyProtocol.Side side;
    private final MessageBuilder peerVatId = new MessageBuilder(4);
    private boolean accepted;
    private final RpcDumper tap = new RpcDumper();

    public TwoPartyVatNetwork(AsynchronousByteChannel channel, RpcTwoPartyProtocol.Side side) {
        this.channel = channel;
        this.side = side;
        this.peerVatId.initRoot(RpcTwoPartyProtocol.VatId.factory).setSide(
                side == RpcTwoPartyProtocol.Side.CLIENT
                        ? RpcTwoPartyProtocol.Side.SERVER
                        : RpcTwoPartyProtocol.Side.CLIENT);
    }

    @Override
    public void close() {
        try {
            this.channel.close();
            this.disconnectPromise.complete(null);
        }
        catch (Exception exc) {
            this.disconnectPromise.completeExceptionally(exc);
        }
    }

    @Override
    public String toString() {
        return this.getSide().toString();
    }

    public RpcTwoPartyProtocol.Side getSide() {
        return side;
    }

    public Connection<RpcTwoPartyProtocol.VatId.Reader> asConnection() {
        return this;
    }

    public CompletableFuture<java.lang.Void> onDisconnect() {
        return this.disconnectPromise.copy();
    }

    @Override
    public Connection<RpcTwoPartyProtocol.VatId.Reader> connect(RpcTwoPartyProtocol.VatId.Reader vatId) {
        return vatId.getSide() != side
                ? this.asConnection()
                : null;
    }

    public CompletableFuture<Connection<RpcTwoPartyProtocol.VatId.Reader>> accept() {
        if (side == RpcTwoPartyProtocol.Side.SERVER & !accepted) {
            accepted = true;
            return CompletableFuture.completedFuture(this.asConnection());
        }
        else {
            // never completes
            return new CompletableFuture<>();
        }
    }

    public RpcTwoPartyProtocol.VatId.Reader getPeerVatId() {
        return this.peerVatId.getRoot(RpcTwoPartyProtocol.VatId.factory).asReader();
    }

    @Override
    public OutgoingRpcMessage newOutgoingMessage(int firstSegmentWordSize) {
        return new OutgoingMessage(firstSegmentWordSize);
    }

    @Override
    public CompletableFuture<IncomingRpcMessage> receiveIncomingMessage() {
        return Serialize.readAsync(channel)
                .thenApply(reader -> {
                    LOGGER.info(() -> this.tap.dump(this.side.name(), reader.getRoot(RpcProtocol.Message.factory)));
                    return (IncomingRpcMessage) new IncomingMessage(reader);
                })
                .exceptionally(exc -> null);
    }

    @Override
    public CompletableFuture<java.lang.Void> shutdown() {
        assert this.previousWrite != null: "Already shut down";

        var result = this.previousWrite.whenComplete((void_, exc) -> {
            try {
                if (this.channel instanceof AsynchronousSocketChannel) {
                    ((AsynchronousSocketChannel)this.channel).shutdownOutput();
                }
            }
            catch (Exception ignored) {
            }
        });

        this.previousWrite = null;
        return result;
    }

    final class OutgoingMessage implements OutgoingRpcMessage {

        private final MessageBuilder message;
        private List<Integer> fds = List.of();

        OutgoingMessage(int firstSegmentWordSize) {
            this.message = new MessageBuilder(firstSegmentWordSize == 0
                    ? BuilderArena.SUGGESTED_FIRST_SEGMENT_WORDS
                    : firstSegmentWordSize);
        }

        @Override
        public AnyPointer.Builder getBody() {
            return message.getRoot(AnyPointer.factory);
        }

        @Override
        public void setFds(List<Integer> fds) {
            this.fds = fds;
        }

        @Override
        public void send() {
            previousWrite = previousWrite.thenCompose(void_ -> {
                LOGGER.info(() -> tap.dump(side.toString(), message.getRoot(RpcProtocol.Message.factory).asReader()));
                return Serialize.writeAsync(channel, message);
            });
        }

        @Override
        public int sizeInWords() {
            int size = 0;
            for (var segment: message.getSegmentsForOutput()) {
                size += segment.position();
            }
            return size / 2;
        }
    }

    final class IncomingMessage implements IncomingRpcMessage {

        private final MessageReader message;
        private final List<Integer> fds;

        IncomingMessage(MessageReader message) {
            this(message, List.of());
        }

        IncomingMessage(MessageReader message, List<Integer> fds) {
            this.message = message;
            this.fds = fds;
        }

        @Override
        public AnyPointer.Reader getBody() {
            return this.message.getRoot(AnyPointer.factory);
        }

        @Override
        public List<Integer> getAttachedFds() {
            return this.fds;
        }
    }
}
