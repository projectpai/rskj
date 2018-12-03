package co.rsk.logger;

import java.util.Iterator;

import org.slf4j.Marker;


public final class LoggingMarker implements Marker {

    public final static Marker SILENT = new LoggingMarker();

    private final String name;

    private LoggingMarker() {
        this.name = "LoggingMarker";
    }

    public String getName() {
        return name;
    }

    public void add(Marker reference) {
        throw new UnsupportedOperationException();
    }

    public boolean hasReferences() {
        return false;
    }

    public boolean hasChildren() {
        return false;
    }

    public Iterator<Marker> iterator() {
        throw new UnsupportedOperationException();
    }

    public boolean remove(Marker referenceToRemove) {
        throw new UnsupportedOperationException();
    }

    public boolean contains(Marker marker) {
        if (marker == null)
            throw new IllegalArgumentException("Marker cannot be null");
        return equals(marker);
    }

    public boolean contains(String name) {
        if (name == null)
            throw new IllegalArgumentException("A marker name cannot be null");
        return this.name.equals(name);
    }

    public boolean equals(Object object) {
        if (this == object)
            return true;
        if (object == null)
            return false;
        if (!(object instanceof Marker))
            return false;
        final Marker other = (Marker)object;
        return name.equals(other.getName());
    }

    public int hashCode() {
        return name.hashCode();
    }
}
