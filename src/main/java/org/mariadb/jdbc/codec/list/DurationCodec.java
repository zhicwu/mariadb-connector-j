/*
 * MariaDB Client for Java
 *
 * Copyright (c) 2012-2014 Monty Program Ab.
 * Copyright (c) 2015-2020 MariaDB Corporation Ab.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along
 * with this library; if not, write to Monty Program Ab info@montyprogram.com.
 *
 */

package org.mariadb.jdbc.codec.list;

import java.io.IOException;
import java.sql.SQLDataException;
import java.time.Duration;
import java.util.Calendar;
import java.util.EnumSet;
import org.mariadb.jdbc.client.ReadableByteBuf;
import org.mariadb.jdbc.client.context.Context;
import org.mariadb.jdbc.client.socket.PacketWriter;
import org.mariadb.jdbc.codec.Codec;
import org.mariadb.jdbc.codec.DataType;
import org.mariadb.jdbc.message.server.ColumnDefinitionPacket;

public class DurationCodec implements Codec<Duration> {

  public static final DurationCodec INSTANCE = new DurationCodec();

  private static final EnumSet<DataType> COMPATIBLE_TYPES =
      EnumSet.of(
          DataType.TIME,
          DataType.DATETIME,
          DataType.TIMESTAMP,
          DataType.VARSTRING,
          DataType.VARCHAR,
          DataType.STRING);

  public String className() {
    return Duration.class.getName();
  }

  public boolean canDecode(ColumnDefinitionPacket column, Class<?> type) {
    return COMPATIBLE_TYPES.contains(column.getType()) && type.isAssignableFrom(Duration.class);
  }

  public boolean canEncode(Object value) {
    return value instanceof Duration;
  }

  @Override
  public Duration decodeText(
      ReadableByteBuf buf, int length, ColumnDefinitionPacket column, Calendar cal)
      throws SQLDataException {

    int[] parts;
    switch (column.getType()) {
      case TIMESTAMP:
      case DATETIME:
        parts = LocalDateTimeCodec.parseTimestamp(buf.readAscii(length));
        if (parts == null) return null;
        return Duration.ZERO
            .plusDays(parts[2] - 1)
            .plusHours(parts[3])
            .plusMinutes(parts[4])
            .plusSeconds(parts[5])
            .plusNanos(parts[6]);

      case TIME:
      case VARCHAR:
      case VARSTRING:
      case STRING:
        parts = LocalTimeCodec.parseTime(buf, length, column);
        Duration d =
            Duration.ZERO
                .plusHours(parts[1])
                .plusMinutes(parts[2])
                .plusSeconds(parts[3])
                .plusNanos(parts[4]);
        if (parts[0] == -1) return d.negated();
        return d;

      default:
        buf.skip(length);
        throw new SQLDataException(
            String.format("Data type %s cannot be decoded as Duration", column.getType()));
    }
  }

  @Override
  public Duration decodeBinary(
      ReadableByteBuf buf, int length, ColumnDefinitionPacket column, Calendar cal) {

    long days = 0;
    int hours = 0;
    int minutes = 0;
    int seconds = 0;
    long microseconds = 0;

    if (column.getType() == DataType.TIME) {
      boolean negate = false;
      if (length > 0) {
        negate = buf.readUnsignedByte() == 0x01;
        if (length > 4) {
          days = buf.readUnsignedInt();
          if (length > 7) {
            hours = buf.readByte();
            minutes = buf.readByte();
            seconds = buf.readByte();
            if (length > 8) {
              microseconds = buf.readInt();
            }
          }
        }
      }

      Duration duration =
          Duration.ZERO
              .plusDays(days)
              .plusHours(hours)
              .plusMinutes(minutes)
              .plusSeconds(seconds)
              .plusNanos(microseconds * 1000);
      if (negate) return duration.negated();
      return duration;
    }

    buf.readUnsignedShort(); // skip year
    buf.readByte(); // skip month
    days = buf.readByte();
    if (length > 4) {
      hours = buf.readByte();
      minutes = buf.readByte();
      seconds = buf.readByte();

      if (length > 7) {
        microseconds = buf.readUnsignedInt();
      }
    }
    return Duration.ZERO
        .plusDays(days - 1)
        .plusHours(hours)
        .plusMinutes(minutes)
        .plusSeconds(seconds)
        .plusNanos(microseconds * 1000);
  }

  @Override
  public void encodeText(
      PacketWriter encoder, Context context, Duration val, Calendar cal, Long maxLen)
      throws IOException {
    long s = val.getSeconds();
    long microSecond = val.getNano() / 1000;
    encoder.writeByte('\'');
    if (microSecond != 0) {
      encoder.writeAscii(
          String.format("%d:%02d:%02d.%06d", s / 3600, (s % 3600) / 60, (s % 60), microSecond));
    } else {
      encoder.writeAscii(String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, (s % 60)));
    }
    encoder.writeByte('\'');
  }

  @Override
  public void encodeBinary(PacketWriter encoder, Context context, Duration value, Calendar cal)
      throws IOException {
    int nano = value.getNano();
    if (nano > 0) {
      encoder.writeByte((byte) 12);
      encodeDuration(encoder, value);
      encoder.writeInt(nano / 1000);
    } else {
      encoder.writeByte((byte) 8);
      encodeDuration(encoder, value);
    }
  }

  private void encodeDuration(PacketWriter encoder, Duration value) throws IOException {
    encoder.writeByte((byte) (value.isNegative() ? 1 : 0));
    encoder.writeInt((int) value.toDays());
    encoder.writeByte((byte) (value.toHours() - 24 * value.toDays()));
    encoder.writeByte((byte) (value.toMinutes() - 60 * value.toHours()));
    encoder.writeByte((byte) (value.getSeconds() - 60 * value.toMinutes()));
  }

  public DataType getBinaryEncodeType() {
    return DataType.TIME;
  }
}
