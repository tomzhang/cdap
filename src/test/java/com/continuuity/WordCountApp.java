/*
 * Copyright 2012-2013 Continuuity,Inc. All Rights Reserved.
 */

package com.continuuity;

import com.continuuity.api.Application;
import com.continuuity.api.ApplicationSpecification;
import com.continuuity.api.annotation.Async;
import com.continuuity.api.annotation.Handle;
import com.continuuity.api.annotation.Output;
import com.continuuity.api.annotation.Process;
import com.continuuity.api.annotation.UseDataSet;
import com.continuuity.api.data.OperationException;
import com.continuuity.api.data.dataset.KeyValueTable;
import com.continuuity.api.data.stream.Stream;
import com.continuuity.api.flow.Flow;
import com.continuuity.api.flow.FlowSpecification;
import com.continuuity.api.flow.flowlet.AbstractFlowlet;
import com.continuuity.api.flow.flowlet.Callback;
import com.continuuity.api.flow.flowlet.FailurePolicy;
import com.continuuity.api.flow.flowlet.FailureReason;
import com.continuuity.api.flow.flowlet.InputContext;
import com.continuuity.api.flow.flowlet.OutputEmitter;
import com.continuuity.api.flow.flowlet.StreamEvent;
import com.continuuity.api.metrics.Metrics;
import com.continuuity.api.procedure.AbstractProcedure;
import com.continuuity.api.procedure.ProcedureRequest;
import com.continuuity.api.procedure.ProcedureResponder;
import com.continuuity.api.procedure.ProcedureResponse;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.Ints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.util.Map;

/**
 * This is a sample word count app that is used in testing in
 * many places.
 */
public class WordCountApp implements Application {

  private static final Logger LOG = LoggerFactory.getLogger(WordCountApp.class);

  /**
   * Configures the {@link com.continuuity.api.Application} by returning an
   * {@link com.continuuity.api.ApplicationSpecification}
   *
   * @return An instance of {@code ApplicationSpecification}.
   */
  @Override
  public ApplicationSpecification configure() {
    return ApplicationSpecification.Builder.with()
      .setName("WordCountApp")
      .setDescription("Application for counting words")
      .withStreams().add(new Stream("text"))
      .withDataSets().add(new KeyValueTable("mydataset"))
      .withFlows().add(new WordCountFlow())
      .withProcedures().add(new WordFrequency()).build();
  }

  public static final class MyRecord {

    private final String title;
    private final String text;
    private final boolean expired;

    public MyRecord(String title, String text, boolean expired) {
      this.title = title;
      this.text = text;
      this.expired = expired;
    }

    public String getTitle() {
      return title;
    }

    public String getText() {
      return text;
    }

    public boolean isExpired() {
      return expired;
    }
  }

  public static class WordCountFlow implements Flow {
    @Override
    public FlowSpecification configure() {
      return FlowSpecification.Builder.with()
        .setName("WordCountFlow")
        .setDescription("Flow for counting words")
        .withFlowlets().add("StreamSource", new StreamSucker())
                       .add(new Tokenizer())
                       .add(new CountByField())
        .connect().fromStream("text").to("StreamSource")
                  .from("StreamSource").to("Tokenizer")
                  .from("Tokenizer").to("CountByField")
        .build();
    }
  }

  public static class StreamSucker extends AbstractFlowlet {
    private OutputEmitter<MyRecord> output;
    private Metrics metrics;

    public StreamSucker() {
      super("StreamSucker");
    }

    public void process(StreamEvent event, InputContext context) throws CharacterCodingException {
      if (!"text".equals(context.getName())) {
        return;
      }

      metrics.count("stream.event", 1);

      ByteBuffer buf = event.getBody();
      output.emit(new MyRecord(
        event.getHeaders().get("title"),
        buf == null ? null : Charsets.UTF_8.newDecoder().decode(buf).toString(),
        false));
    }
  }

  public static class Tokenizer extends AbstractFlowlet {
    @Output("field")
    private OutputEmitter<Map<String, String>> outputMap;

    public Tokenizer() {
      super("Tokenizer");
    }

    @Process
    public void foo(MyRecord data) {
      tokenize(data.getTitle(), "title");
      tokenize(data.getText(), "text");
    }

    private void tokenize(String str, String field) {
      if (str == null) {
        return;
      }
      final String delimiters = "[ .-]";
      for (String token : str.split(delimiters)) {
        outputMap.emit(ImmutableMap.of("field", field, "word", token));
      }
    }
  }

  @Async
  public static class CountByField extends AbstractFlowlet implements Callback {
    @UseDataSet("mydataset")
    private KeyValueTable counters;

    public CountByField() {
      super("CountByField");
    }

    @Process("field")
    public void process(Map<String, String> fieldToken) throws OperationException {
      LOG.info("process count by field: " + fieldToken);

      String token = fieldToken.get("word");
      if (token == null) {
        return;
      }
      String field = fieldToken.get("field");
      if (field != null) {
        token = field + ":" + token;
      }

      this.counters.increment(token.getBytes(Charsets.UTF_8), 1);
    }

    @Override
    public void onSuccess(@Nullable Object input, @Nullable InputContext inputContext) {
      LOG.info("Success: " + input);
    }

    @Override
    public FailurePolicy onFailure(@Nullable Object input, @Nullable InputContext inputContext, FailureReason reason) {
      return FailurePolicy.RETRY;
    }
  }

  public static class WordFrequency extends AbstractProcedure {
    @UseDataSet("mydataset")
    private KeyValueTable counters;

    public WordFrequency() {
      super("WordFrequency");
    }

    @Handle("wordfreq")
    public void handle(ProcedureRequest request, ProcedureResponder responder) throws OperationException, IOException {
      String word = request.getArgument("word");
      int count = Ints.fromByteArray(this.counters.read(word.getBytes(Charsets.UTF_8)));
      ProcedureResponse.Writer writer = responder.response(new ProcedureResponse(ProcedureResponse.Code.SUCCESS));
      try {
        writer.write(ByteBuffer.wrap(Integer.toString(count).getBytes(Charsets.UTF_8)));
      } finally {
        writer.close();
      }
    }
  }
}
