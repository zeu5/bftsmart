package bftsmart.communication.server;

import bftsmart.communication.SystemMessage;
import bftsmart.consensus.messages.ConsensusMessage;
import bftsmart.reconfiguration.ServerViewController;
import bftsmart.reconfiguration.util.NetrixConfiguration;
import bftsmart.tom.ServiceReplica;
import com.google.gson.Gson;
import com.google.gson.JsonPrimitive;
import io.github.netrixframework.comm.Message;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.netrixframework.*;
import javax.crypto.SecretKey;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Objects;
import java.util.Vector;
import java.util.concurrent.LinkedBlockingQueue;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;

public class NetrixCommunicationLayer extends CommunicationLayer{
    private Logger logger = LoggerFactory.getLogger(this.getClass());
    private ServerViewController controller;
    private ServiceReplica replica;
    private LinkedBlockingQueue<SystemMessage> inQueue;
    private NetrixClient client;

    private boolean doWork = true;

    public NetrixCommunicationLayer(ServerViewController controller,
                                    LinkedBlockingQueue<SystemMessage> inQueue,
                                    ServiceReplica replica) {
        this.controller = controller;
        this.inQueue = inQueue;
        this.replica = replica;

        NetrixConfiguration c = controller.getStaticConf();

        NetrixClientConfig config = new NetrixClientConfig(
                Integer.toString(replica.getId()),
                c.getNetrixAddr(),
                c.getNetrixClientServerAddr(),
                c.getNetrixClientServerPort(),
                c.getNetrixClientAdvAddr(),
                new HashMap<String, String>()
        );
        this.client = NetrixClientSingleton.init(config, replica);
    }

    public SecretKey getSecretKey(int id) {
        return null;
    }

    public void updateConnections() {}

    public final void send(int[] targets, SystemMessage sm, boolean useMAC) {
        byte[] data;
        if(sm instanceof ConsensusMessage) {
            logger.info("Sending a consensus message");
            ConsensusMessage cm = (ConsensusMessage) sm;
            Gson gson = GsonHelper.gson;
            data = gson.toJson(cm).getBytes(StandardCharsets.UTF_8);
        } else {
            ByteArrayOutputStream bOut = new ByteArrayOutputStream(248);
            try {
                new ObjectOutputStream(bOut).writeObject(sm);
            } catch (IOException e) {
                logger.error("Failed to serialize message", e);
            }
            data = bOut.toByteArray();
        }

        for(int t: targets) {
            try {
                this.client.sendMessage(new Message(
                        Integer.toString(t),
                        sm.getClass() == ConsensusMessage.class? ((ConsensusMessage) sm).getPaxosVerboseType(): "other",
                        data
                ));
            } catch (IOException e) {
                logger.error("Failed to send message", e);
            }
        }
    }

    public void shutdown() {
        doWork = false;
        this.client.stopClient();
    }

    public void joinViewReceived() {
    }

    @Override
    public void run() {
        this.client.start();
        try {
            this.client.setReady();
        } catch (Exception ignored) {

        }
        while(doWork) {
            Vector<Message> messages = this.client.getMessages();
            for(Message m: messages) {
                try {
                    SystemMessage sm;
                    if (!Objects.equals(m.getType(), "other")) {
                        Gson gson = GsonHelper.gson;
                        sm = gson.fromJson(new String(m.getData(), CharsetUtil.UTF_8), ConsensusMessage.class);
                    } else {
                        sm = (SystemMessage) (new ObjectInputStream(new ByteArrayInputStream(m.getData()))
                                .readObject());
                    }
                    sm.authenticated = true;
                    if(!inQueue.offer(sm)) {
                        logger.warn("Inqueue full (message from " + sm.getSender() + " discarded).");
                    }
                } catch (Exception e) {
                    logger.info("Error parsing message: "+e.getMessage());
                }
            }
        }
    }
}
