package graphql.servlet.internal;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import graphql.ExecutionResult;
import graphql.servlet.ApolloSubscriptionConnectionListener;
import graphql.servlet.GraphQLSingleInvocationInput;
import graphql.servlet.SubscriptionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.websocket.CloseReason;
import javax.websocket.Session;
import javax.websocket.server.HandshakeRequest;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static graphql.servlet.internal.ApolloSubscriptionProtocolHandler.OperationMessage.Type.GQL_COMPLETE;
import static graphql.servlet.internal.ApolloSubscriptionProtocolHandler.OperationMessage.Type.GQL_CONNECTION_TERMINATE;
import static graphql.servlet.internal.ApolloSubscriptionProtocolHandler.OperationMessage.Type.GQL_DATA;
import static graphql.servlet.internal.ApolloSubscriptionProtocolHandler.OperationMessage.Type.GQL_ERROR;

/**
 * https://github.com/apollographql/subscriptions-transport-ws/blob/master/PROTOCOL.md
 *
 * @author Andrew Potter
 */
public class ApolloSubscriptionProtocolHandler extends SubscriptionProtocolHandler {

    private static final Logger log = LoggerFactory.getLogger(ApolloSubscriptionProtocolHandler.class);
    private static final CloseReason TERMINATE_CLOSE_REASON = new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "client requested " + GQL_CONNECTION_TERMINATE.getType());

    private final SubscriptionHandlerInput input;
    private final SubscriptionSender sender;
    private final ApolloSubscriptionKeepAliveRunner keepAliveRunner;
    private final ApolloSubscriptionConnectionListener connectionListener;

    public ApolloSubscriptionProtocolHandler(SubscriptionHandlerInput subscriptionHandlerInput,
                                             ApolloSubscriptionConnectionListener connectionListener,
                                             SubscriptionSender subscriptionSender,
                                             ApolloSubscriptionKeepAliveRunner keepAliveRunner) {
        this.input = subscriptionHandlerInput;
        this.connectionListener = connectionListener;
        this.sender = subscriptionSender;
        this.keepAliveRunner = keepAliveRunner;
    }

    @Override
    public void onMessage(HandshakeRequest request, Session session, WsSessionSubscriptions subscriptions, String text) {
        OperationMessage message;
        try {
            message = input.getGraphQLObjectMapper().getJacksonMapper().readValue(text, OperationMessage.class);
        } catch(Throwable t) {
            log.warn("Error parsing message", t);
            sendMessage(session, OperationMessage.Type.GQL_CONNECTION_ERROR, null);
            return;
        }

        switch (message.getType()) {
            case GQL_CONNECTION_INIT:
                try {
                    Optional<Object> connectionResponse = connectionListener.onConnect(message.getPayload());
                    connectionResponse.ifPresent(it -> session.getUserProperties().put(ApolloSubscriptionConnectionListener.CONNECT_RESULT_KEY, it));
                } catch (SubscriptionException e) {
                    sendMessage(session, OperationMessage.Type.GQL_CONNECTION_ERROR, message.getId(), e.getPayload());
                    return;
                }

                sendMessage(session, OperationMessage.Type.GQL_CONNECTION_ACK, message.getId());

                if (connectionListener.isKeepAliveEnabled()) {
                    keepAliveRunner.keepAlive(session);
                }
                break;

            case GQL_START:
                GraphQLSingleInvocationInput graphQLSingleInvocationInput = createInvocationInput(session, message);
                handleSubscriptionStart(
                    session,
                    subscriptions,
                    message.id,
                    input.getQueryInvoker().query(graphQLSingleInvocationInput)
                );
                break;

            case GQL_STOP:
                unsubscribe(subscriptions, message.id);
                break;

            case GQL_CONNECTION_TERMINATE:
                keepAliveRunner.abort(session);
                try {
                    session.close(TERMINATE_CLOSE_REASON);
                } catch (IOException e) {
                    log.error("Error closing websocket session!", e);
                }
                break;

            default:
                throw new IllegalArgumentException("Unknown message type: " + message.getType());
        }
    }

    private GraphQLSingleInvocationInput createInvocationInput(Session session, OperationMessage message) {
        GraphQLRequest graphQLRequest = input.getGraphQLObjectMapper()
                .getJacksonMapper()
                .convertValue(message.getPayload(), GraphQLRequest.class);
        HandshakeRequest handshakeRequest = (HandshakeRequest) session.getUserProperties()
                .get(HandshakeRequest.class.getName());

        return input.getInvocationInputFactory().create(graphQLRequest, session, handshakeRequest);
    }

    @SuppressWarnings("unchecked")
    private void handleSubscriptionStart(Session session, WsSessionSubscriptions subscriptions, String id, ExecutionResult executionResult) {
        executionResult = input.getGraphQLObjectMapper().sanitizeErrors(executionResult);

        if (input.getGraphQLObjectMapper().areErrorsPresent(executionResult)) {
            sendMessage(session, OperationMessage.Type.GQL_ERROR, id, input.getGraphQLObjectMapper().convertSanitizedExecutionResult(executionResult, false));
            return;
        }

        subscribe(session, executionResult, subscriptions, id);
    }

    @Override
    protected void sendDataMessage(Session session, String id, Object payload) {
        sendMessage(session, GQL_DATA, id, payload);
    }

    @Override
    protected void sendErrorMessage(Session session, String id) {
        keepAliveRunner.abort(session);
        sendMessage(session, GQL_ERROR, id);
    }

    @Override
    protected void sendCompleteMessage(Session session, String id) {
        keepAliveRunner.abort(session);
        sendMessage(session, GQL_COMPLETE, id);
    }

    private void sendMessage(Session session, OperationMessage.Type type, String id) {
        sendMessage(session, type, id, null);
    }

    private void sendMessage(Session session, OperationMessage.Type type, String id, Object payload) {
        sender.send(session, new OperationMessage(type, id, payload));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OperationMessage {
        private Type type;
        private String id;
        private Object payload;

        public OperationMessage() {
        }

        public OperationMessage(Type type, String id, Object payload) {
            this.type = type;
            this.id = id;
            this.payload = payload;
        }

        static OperationMessage newKeepAliveMessage() {
            return new OperationMessage(Type.GQL_CONNECTION_KEEP_ALIVE, null, null);
        }

        public Type getType() {
            return type;
        }

        public String getId() {
            return id;
        }

        public Object getPayload() {
            return payload;
        }

        public enum Type {

            // Server Messages
            GQL_CONNECTION_ACK("connection_ack"),
            GQL_CONNECTION_ERROR("connection_error"),
            GQL_CONNECTION_KEEP_ALIVE("ka"),
            GQL_DATA("data"),
            GQL_ERROR("error"),
            GQL_COMPLETE("complete"),

            // Client Messages
            GQL_CONNECTION_INIT("connection_init"),
            GQL_CONNECTION_TERMINATE("connection_terminate"),
            GQL_START("start"),
            GQL_STOP("stop");

            private static final Map<String, Type> reverseLookup = new HashMap<>();

            static {
                for(Type type: Type.values()) {
                    reverseLookup.put(type.getType(), type);
                }
            }

            private final String type;

            Type(String type) {
                this.type = type;
            }

            @JsonCreator
            public static Type findType(String type) {
                return reverseLookup.get(type);
            }

            @JsonValue
            public String getType() {
                return type;
            }
        }
    }

}
