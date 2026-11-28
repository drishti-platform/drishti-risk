/*
 * Drishti Risk · market and counterparty risk as engines.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.risk.market.engine;

import com.ash.engine.DoubleVectors;
import com.ash.engine.EngineException;
import com.ash.engine.FloatVectors;
import com.ash.engine.Image;
import com.ash.engine.ImageWriter;
import com.ash.engine.RowSet;
import com.ash.risk.kernel.vector.MappedMatrix;
import com.ash.risk.kernel.vector.Vectors;
import com.ash.risk.market.calc.VarHotFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;

/**
 * A book in the host's kept image (ENGINE_SPEC 18 rule 8; R2 speed design 2.3): the hot layout Drishti's VaR keeps a date in
 * ({@link VarHotFile}: the skeleton's sections, the float32 position and trade rows, the float64 aggregates) written through the host's
 * {@link ImageWriter} and read back from its {@link Image} in place. The engine names the image; the host owns, maps, leases and keys it.
 */
final class MarketImage {

    private MarketImage() {
    }

    /** The hot layout's parts written into an image: each section a byte part, each matrix a float32 or float64 part. */
    static VarHotFile.Sink sink(ImageWriter w) {
        return new VarHotFile.Sink() {
            @Override
            public void section(String name, byte[] bytes) throws IOException {
                try {
                    w.bytes(name, ByteBuffer.wrap(bytes));
                } catch (EngineException e) {
                    throw new IOException(e.getMessage(), e);
                }
            }

            @Override
            public void matrix(String name, int rows, int width, MappedMatrix.Type type, VarHotFile.RowSource source) throws IOException {
                double[] row = new double[width];
                try {
                    if (type == MappedMatrix.Type.FLOAT32) {
                        w.floats(name, rows, width, (i, into) -> {
                            source.row(i, row);
                            for (int j = 0; j < width; j++) {
                                into[j] = (float) row[j];                // narrowed once, as the hot file narrows
                            }
                        });
                    } else {
                        w.doubles(name, rows, width, (i, into) -> source.row(i, into));
                    }
                } catch (EngineException e) {
                    throw new IOException(e.getMessage(), e);
                }
            }
        };
    }

    /** The hot layout read from an image: a section is its byte part, a matrix its rows read in place. */
    static VarHotFile.Parts parts(Image image) {
        return new VarHotFile.Parts() {
            @Override
            public ByteBuffer section(String name) {
                return image.parts().contains(name) ? image.bytes(name) : null;
            }

            @Override
            public Vectors matrix(String name, int rows, int width, MappedMatrix.Type type) throws IOException {
                Vectors v = type == MappedMatrix.Type.FLOAT32 ? new Floats(image.floats(name)) : new Doubles(image.doubles(name));
                if (v.rows() != rows || rows > 0 && v.width() != width) {
                    throw new IOException("the image's " + name + " holds " + v.rows() + " rows of " + v.width() + ", not " + rows + " of " + width);
                }
                return v;
            }
        };
    }

    /** The pieces a mapped matrix of this shape is cut into ({@link MappedMatrix#open}): a sum splits by them, so the same bits. */
    private static int pieces(int rows, int width, int bytes) {
        long rowBytes = (long) Math.max(1, width) * bytes;
        int per = (int) Math.max(1, (Integer.MAX_VALUE - 4096L) / rowBytes);
        return rows == 0 ? 1 : Math.max(1, (rows + per - 1) / per);
    }

    /** float32 rows of an image as the book's vectors: every read through the host's view (a read after its lease refuses). */
    private record Floats(FloatVectors v) implements Vectors {
        @Override public int width() { return v.width(); }
        @Override public int rows() { return v.rows(); }
        @Override public int chunkCount() { return pieces(v.rows(), v.width(), Float.BYTES); }
        @Override public long bytes() { return (long) v.rows() * v.width() * Float.BYTES; }
        @Override public double value(int row, int j) { return v.value(row, j); }

        @Override
        public void copy(int row, double[] out) {
            java.util.Arrays.fill(out, 0, v.width(), 0.0);
            addInto(out, row);
        }

        @Override
        public void addInto(double[] acc, int row) {
            v.addInto(acc, RowSet.range(row, row + 1));
        }

        @Override
        public void addRangeInto(double[] acc, int from, int to) {
            if (from < to) {
                v.addInto(acc, RowSet.range(from, to));
            }
        }
    }

    /** float64 rows of an image (the aggregates): read exactly ({@link #read}: {@code -0.0} stays). */
    private record Doubles(DoubleVectors v) implements Vectors {
        @Override public int width() { return v.width(); }
        @Override public int rows() { return v.rows(); }
        @Override public int chunkCount() { return pieces(v.rows(), v.width(), Double.BYTES); }
        @Override public long bytes() { return (long) v.rows() * v.width() * Double.BYTES; }
        @Override public double value(int row, int j) { return v.value(row, j); }

        @Override
        public void read(int row, double[] out) {
            v.row(row).get(0, out, 0, v.width());
        }

        @Override
        public void copy(int row, double[] out) {
            java.util.Arrays.fill(out, 0, v.width(), 0.0);
            addInto(out, row);
        }

        @Override
        public void addInto(double[] acc, int row) {
            v.addInto(acc, RowSet.range(row, row + 1));
        }

        @Override
        public void addRangeInto(double[] acc, int from, int to) {
            if (from < to) {
                v.addInto(acc, RowSet.range(from, to));
            }
        }
    }

    /** An I/O failure of the image's layout in the engine's words. */
    static EngineException failed(String name, IOException e) {
        return new EngineException("ENG-9000", EngineException.Kind.FAILED, "the kept image " + name + " could not be used: " + e.getMessage(),
                java.util.Map.of(), e);
    }

    static UncheckedIOException unchecked(IOException e) {
        return new UncheckedIOException(e);
    }
}
