// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.javaapi.data.codegen;

import com.daml.ledger.javaapi.data.DamlRecord;
import java.util.List;

/** Compatibility wrapper for newer generated Java bindings. */
public final class PreparedRecord {
  private final List<DamlRecord.Field> expectedFields;

  public PreparedRecord(List<DamlRecord.Field> expectedFields) {
    this.expectedFields = expectedFields;
  }

  public List<DamlRecord.Field> getExpectedFields() {
    return expectedFields;
  }
}
