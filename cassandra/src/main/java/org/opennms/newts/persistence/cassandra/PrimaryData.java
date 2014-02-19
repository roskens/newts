package org.opennms.newts.persistence.cassandra;


import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;

import org.opennms.newts.api.Duration;
import org.opennms.newts.api.Measurement;
import org.opennms.newts.api.MetricType;
import org.opennms.newts.api.Results.Row;
import org.opennms.newts.api.Timestamp;
import org.opennms.newts.api.ValueType;

import com.google.common.collect.Maps;


public class PrimaryData implements Iterator<Row>, Iterable<Row> {

    private class Accumulation {
        private long known = 0, unknown = 0;
        private ValueType<?> value;
        private MetricType type;

        private Accumulation(MetricType type) {
            value = ValueType.compose(0, type);
            this.type = type;
        }

        private double elapsed() {
            return known + unknown;
        }

        private ValueType<?> average() {
            return isValid() ? value.divideBy(known) : null;
        }

        private boolean isValid() {
            return unknown < (elapsed() / 2);
        }

        private void reset() {
            known = unknown = 0;
            value = ValueType.compose(0, type);
        }

    }

    private final String m_resource;
    private final String[] m_metrics;
    private final Iterator<Timestamp> m_timestamps;
    private final Duration m_interval;
    private final Map<String, Duration> m_heartbeats;
    private final Iterator<Row> m_input;
    private final Map<String, Measurement> m_lastUpdates = Maps.newHashMap();
    private final Map<String, Accumulation> m_accumulation = Maps.newHashMap();

    private Row m_current = null;

    public PrimaryData(String resource, String[] metrics, Timestamp start, Timestamp end, Duration interval, Map<String, Duration> heartbeats, Iterator<Row> input) {
        m_resource = checkNotNull(resource, "resource argument");
        m_metrics = checkNotNull(metrics, "metrics argument");
        checkNotNull(start, "start argument");
        checkNotNull(end, "end argument");
        m_interval = checkNotNull(interval, "interval argument");
        m_heartbeats = checkNotNull(heartbeats, "hearbeats argument");
        m_input = checkNotNull(input, "input argument");

        m_timestamps = new IntervalGenerator(start, end, interval);

        if (m_input.hasNext()) m_current = m_input.next();

    }

    @Override
    public boolean hasNext() {
        return m_timestamps.hasNext();
    }

    @Override
    public Row next() {

        if (!hasNext()) throw new NoSuchElementException();

        Row output = new Row(m_timestamps.next(), m_resource);

        while (m_current != null) {
            accumulate(m_current, output.getTimestamp());

            if (m_current.getTimestamp().gte(output.getTimestamp())) {
                break;
            }

            if (m_input.hasNext())
                m_current = m_input.next();
            else m_current = null;

        }

        for (String name : m_metrics) {
            Accumulation accumulation = m_accumulation.get(name);
            output.addMeasurement(new Measurement(
                    output.getTimestamp(),
                    output.getResource(),
                    name,
                    accumulation.type,
                    accumulation.average()));
        }

        // If input is greater than row, accumulate remainder for next row
        if (m_current != null) {
            for (String name : m_metrics) {
                Accumulation accumulation = m_accumulation.get(name);

                if (m_current.getTimestamp().gt(output.getTimestamp())) {
                    Duration elapsed = m_current.getTimestamp().minus(output.getTimestamp());
                    if (elapsed.lt(getHeartbeat(name))) {
                        accumulation.known = elapsed.asMillis();
                        accumulation.unknown = 0;
                        accumulation.value = m_current.getMeasurement(name).getValue().times(elapsed.asMillis());
                    }
                    else {
                        accumulation.reset();
                        accumulation.unknown = elapsed.asMillis();
                    }
                }
                else {
                    accumulation.reset();
                }
            }
        }

        return output;
    }

    private void accumulate(Row row, Timestamp intervalCeiling) {

        for (String name : m_metrics) {
            Measurement current, last;

            current = row.getMeasurement(name);

            if (current == null) {
                continue;
            }

            last = m_lastUpdates.get(current.getName());

            if (last == null) {
                m_lastUpdates.put(current.getName(), current);
                continue;
            }

            Duration elapsed;

            if (row.getTimestamp().gt(intervalCeiling)) {
                elapsed = intervalCeiling.minus(last.getTimestamp());
            }
            else {
                elapsed = current.getTimestamp().minus(last.getTimestamp());
            }

            Accumulation accumulation = m_accumulation.get(current.getName());

            if (accumulation == null) { // Create
                accumulation = new Accumulation(current.getType());
                m_accumulation.put(current.getName(), accumulation);
            }

            // FIXME: what happens if metric type changes mid-stream?

            if (elapsed.lt(getHeartbeat(current.getName()))) {
                accumulation.known += elapsed.asMillis();
                accumulation.value = accumulation.value.plus(current.getValue().times(elapsed.asMillis()));
            }
            else {
                accumulation.unknown += elapsed.asMillis();
            }

            m_lastUpdates.put(current.getName(), current);
        }
    }

    /*
     * FIXME: If a multiple of interval is a reasonable way of providing a default (is using a
     * default is even reasonable at all?), then do something better than the hard-coded value here.
     */
    private Duration getHeartbeat(String metricName) {
        return m_heartbeats.containsKey(metricName) ? m_heartbeats.get(metricName) : m_interval.times(2);
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Iterator<Row> iterator() {
        return this;
    }

}
