package ru.it_spectrum.ai.jdbc.mcp.dialect;

/**
 * The connection's engine cannot answer this request at all — a generic JDBC driver exposes no view
 * sources, no plans, no sequences. Tools report it with the error kind {@code unsupported}, so an
 * agent knows to stop retrying rather than fix its arguments.
 *
 * <p>Extends {@link IllegalArgumentException} so every tool that maps argument errors reports it
 * without a catch clause of its own.
 */
public class UnsupportedFeatureException extends IllegalArgumentException {

    public UnsupportedFeatureException(String message) {
        super(message);
    }
}
