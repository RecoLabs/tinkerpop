package com.tinkerpop.gremlin.computer.gremlin;

import com.tinkerpop.blueprints.Direction;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Element;
import com.tinkerpop.blueprints.Property;
import com.tinkerpop.blueprints.Vertex;
import com.tinkerpop.blueprints.computer.GraphMemory;
import com.tinkerpop.blueprints.computer.MessageType;
import com.tinkerpop.blueprints.computer.Messenger;
import com.tinkerpop.blueprints.computer.VertexProgram;
import com.tinkerpop.blueprints.util.StreamFactory;
import com.tinkerpop.gremlin.pipes.Gremlin;
import com.tinkerpop.gremlin.pipes.Pipe;
import com.tinkerpop.gremlin.pipes.util.Holder;
import com.tinkerpop.gremlin.pipes.util.SingleIterator;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public class GremlinVertexProgram implements VertexProgram<GremlinMessage> {

    private MessageType.Global global = MessageType.Global.of(GREMLIN_MESSAGE);

    private static final String GREMLIN_MESSAGE = "gremlinMessage";
    private static final String GREMLIN_PIPELINE = "gremlinPipeline";
    private static final String GREMLINS = "gremlins";
    private final Supplier<Gremlin> gremlin;

    public GremlinVertexProgram(Supplier<Gremlin> gremlin) {
        this.gremlin = gremlin;
    }

    public void setup(final GraphMemory graphMemory) {
        graphMemory.setIfAbsent(GREMLIN_PIPELINE, this.gremlin);
    }

    public void execute(final Vertex vertex, final Messenger<GremlinMessage> messenger, final GraphMemory graphMemory) {

        if (graphMemory.isInitialIteration()) {
            messenger.sendMessage(vertex, MessageType.Global.of(GREMLIN_MESSAGE, vertex), GremlinMessage.of(vertex, 1l));
        } else {
            final CounterMap<Object> counters = new CounterMap<>();
            final Set<Object> objects = new HashSet<>();
            final Pipe pipe = getCurrentPipe(graphMemory);

            messenger.receiveMessages(vertex, global).forEach(m -> {
                if (m.destination.equals(GremlinMessage.Destination.VERTEX)) {
                    objects.add(vertex);
                    counters.incrValue(vertex, m.counts);
                } else if (m.destination.equals(GremlinMessage.Destination.EDGE)) {
                    final List<Edge> edges = this.getEdges(vertex, m);
                    objects.addAll(edges);
                    edges.forEach(e -> counters.incrValue(e, m.counts));
                } else if (m.destination.equals(GremlinMessage.Destination.PROPERTY)) {
                    final List<Property> properties = this.getProperties(vertex, m);
                    objects.addAll(properties);
                    properties.forEach(p -> counters.incrValue(p, m.counts));
                } else {
                    throw new UnsupportedOperationException("This object type has not been handled yet: " + m);
                }
            });

            objects.forEach(start -> {
                pipe.addStarts(new SingleIterator<Holder>(new Holder<>(Pipe.NONE, start)));
                pipe.forEachRemaining(h -> {
                    final Object end = ((Holder<Object>) h).get();
                    // System.out.println(start + "-->" + end + " [" + counters.get(start) + "]");
                    messenger.sendMessage(
                            vertex,
                            MessageType.Global.of(GREMLIN_MESSAGE, Messenger.getHostingVertices(end)),
                            GremlinMessage.of(end, counters.get(start)));
                });
            });
            counters.forEach((k, v) -> {
                if (k instanceof Element) {
                    ((Element) k).setProperty(GREMLINS, v);
                } else {
                    ((Property) k).setAnnotation(GREMLINS, v);
                }
            });
        }
    }

    private List<Edge> getEdges(final Vertex vertex, final GremlinMessage message) {
        return StreamFactory.stream(vertex.query().direction(Direction.BOTH).edges())
                .filter(e -> e.getId().equals(message.elementId))
                .collect(Collectors.toList());
    }

    private List<Property> getProperties(final Vertex vertex, final GremlinMessage message) {
        if (message.elementId.equals(vertex.getId())) {
            final Property property = vertex.getProperty(message.propertyKey);
            return Arrays.asList(property);
        } else {
            return StreamFactory.stream(vertex.query().direction(Direction.BOTH).edges())
                    .filter(e -> e.getId().equals(message.elementId))
                    .map(e -> e.getProperty(message.propertyKey))
                    .collect(Collectors.toList());
        }
    }

    private Pipe getCurrentPipe(final GraphMemory graphMemory) {
        final Supplier<Gremlin> gremlin = graphMemory.get(GREMLIN_PIPELINE);
        return (Pipe) gremlin.get().getPipes().get(graphMemory.getIteration());
    }

    ////////// GRAPH COMPUTER METHODS

    public boolean terminate(final GraphMemory graphMemory) {
        Supplier<Gremlin> gremlin = graphMemory.get(GREMLIN_PIPELINE);
        return !(graphMemory.getIteration() < gremlin.get().getPipes().size());
    }

    public Map<String, KeyType> getComputeKeys() {
        return VertexProgram.ofComputeKeys(GREMLINS, KeyType.VARIABLE);
    }

    public static Builder create() {
        return new Builder();
    }

    public static class Builder {
        private Supplier<Gremlin> gremlin;

        public Builder gremlin(final Supplier<Gremlin> gremlin) {
            this.gremlin = gremlin;
            return this;
        }

        public GremlinVertexProgram build() {
            return new GremlinVertexProgram(this.gremlin);
        }
    }
}