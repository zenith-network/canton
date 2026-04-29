// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.javaapi.data.codegen.json;

import com.daml.ledger.javaapi.data.codegen.UnknownTrailingFieldPolicy;
import java.io.IOException;

@FunctionalInterface
public interface JsonLfDecoder<T> {
  @FunctionalInterface
  interface DecoderWithPolicy<T> {
    T decode(JsonLfReader r, UnknownTrailingFieldPolicy policy) throws Error;
  }

  public T decode(JsonLfReader r) throws Error;

  /** Compatibility overload for newer generated Java bindings. */
  default T decode(JsonLfReader r, UnknownTrailingFieldPolicy policy) throws Error {
    return decode(r);
  }

  /** Compatibility factory for newer generated Java bindings. */
  static <T> JsonLfDecoder<T> create(DecoderWithPolicy<T> decoder) {
    return new JsonLfDecoder<>() {
      @Override
      public T decode(JsonLfReader r) throws Error {
        return decoder.decode(r, UnknownTrailingFieldPolicy.STRICT);
      }

      @Override
      public T decode(JsonLfReader r, UnknownTrailingFieldPolicy policy) throws Error {
        return decoder.decode(r, policy);
      }
    };
  }

  public static class Error extends IOException {
    public final JsonLfReader.Location location;
    private final String msg;

    public Error(String message, JsonLfReader.Location loc) {
      this(message, loc, null);
    }

    public Error(String message, JsonLfReader.Location loc, Throwable cause) {
      super(String.format("%s at line: %d, column: %d", message, loc.line, loc.column), cause);
      this.msg = message;
      this.location = loc;
    }

    public Error fromStartLocation(JsonLfReader.Location start) {
      return new Error(this.msg, start.advance(this.location), this.getCause());
    }
  }
}
