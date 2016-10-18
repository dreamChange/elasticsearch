/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.input.chain;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.script.Script;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.common.http.HttpRequestTemplate;
import org.elasticsearch.xpack.common.http.auth.basic.BasicAuth;
import org.elasticsearch.xpack.watcher.condition.AlwaysCondition;
import org.elasticsearch.xpack.watcher.condition.ScriptCondition;
import org.elasticsearch.xpack.watcher.execution.TriggeredExecutionContext;
import org.elasticsearch.xpack.watcher.execution.WatchExecutionContext;
import org.elasticsearch.xpack.watcher.input.InputFactory;
import org.elasticsearch.xpack.watcher.input.InputRegistry;
import org.elasticsearch.xpack.watcher.input.http.HttpInput;
import org.elasticsearch.xpack.watcher.input.simple.ExecutableSimpleInput;
import org.elasticsearch.xpack.watcher.input.simple.SimpleInput;
import org.elasticsearch.xpack.watcher.input.simple.SimpleInputFactory;
import org.elasticsearch.xpack.watcher.trigger.schedule.IntervalSchedule;
import org.elasticsearch.xpack.watcher.trigger.schedule.ScheduleTrigger;
import org.elasticsearch.xpack.watcher.trigger.schedule.ScheduleTriggerEvent;
import org.elasticsearch.xpack.watcher.watch.Payload;
import org.elasticsearch.xpack.watcher.watch.Watch;
import org.elasticsearch.xpack.watcher.watch.WatchStatus;
import org.joda.time.DateTime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static java.util.Collections.emptyMap;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.xpack.watcher.actions.ActionBuilders.loggingAction;
import static org.elasticsearch.xpack.watcher.client.WatchSourceBuilders.watchBuilder;
import static org.elasticsearch.xpack.watcher.input.InputBuilders.chainInput;
import static org.elasticsearch.xpack.watcher.input.InputBuilders.httpInput;
import static org.elasticsearch.xpack.watcher.input.InputBuilders.simpleInput;
import static org.elasticsearch.xpack.watcher.trigger.TriggerBuilders.schedule;
import static org.elasticsearch.xpack.watcher.trigger.schedule.Schedules.interval;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.joda.time.DateTimeZone.UTC;

public class ChainInputTests extends ESTestCase {

    /* note, first line does not need to be parsed
    "chain" : {
      "inputs" : [
        { "first" : { "simple" : { "foo" : "bar" } } },
        { "second" : { "simple" : { "spam" : "eggs" } } }
      ]
    }
     */
    public void testThatExecutionWorks() throws Exception {
        Map<String, InputFactory> factories = new HashMap<>();
        factories.put("simple", new SimpleInputFactory(Settings.EMPTY));

        // hackedy hack...
        InputRegistry inputRegistry = new InputRegistry(Settings.EMPTY, factories);
        ChainInputFactory chainInputFactory = new ChainInputFactory(Settings.EMPTY, inputRegistry);
        factories.put("chain", chainInputFactory);

        XContentBuilder builder = jsonBuilder().startObject().startArray("inputs")
                .startObject().startObject("first").startObject("simple").field("foo", "bar").endObject().endObject().endObject()
                .startObject().startObject("second").startObject("simple").field("spam", "eggs").endObject().endObject().endObject()
                .endArray().endObject();

        // first pass JSON and check for correct inputs
        XContentParser parser = XContentFactory.xContent(builder.bytes()).createParser(builder.bytes());
        parser.nextToken();
        ChainInput chainInput = chainInputFactory.parseInput("test", parser, false);

        assertThat(chainInput.getInputs(), hasSize(2));
        assertThat(chainInput.getInputs().get(0).v1(), is("first"));
        assertThat(chainInput.getInputs().get(0).v2(), instanceOf(SimpleInput.class));
        assertThat(chainInput.getInputs().get(1).v1(), is("second"));
        assertThat(chainInput.getInputs().get(1).v2(), instanceOf(SimpleInput.class));

        // now execute
        ExecutableChainInput executableChainInput = chainInputFactory.createExecutable(chainInput);
        ChainInput.Result result = executableChainInput.execute(createContext(), new Payload.Simple());
        Payload payload = result.payload();
        assertThat(payload.data(), hasKey("first"));
        assertThat(payload.data(), hasKey("second"));
        assertThat(payload.data().get("first"), instanceOf(Map.class));
        assertThat(payload.data().get("second"), instanceOf(Map.class));

        // final payload check
        Map<String, Object> firstPayload = (Map<String,Object>) payload.data().get("first");
        Map<String, Object> secondPayload = (Map<String,Object>) payload.data().get("second");
        assertThat(firstPayload, hasEntry("foo", "bar"));
        assertThat(secondPayload, hasEntry("spam", "eggs"));
    }

    public void testToXContent() throws Exception {
        ChainInput chainedInput = chainInput()
                .add("first", simpleInput("foo", "bar"))
                .add("second", simpleInput("spam", "eggs"))
                .build();

        XContentBuilder builder = XContentFactory.contentBuilder(XContentType.JSON);
        chainedInput.toXContent(builder, ToXContent.EMPTY_PARAMS);

        assertThat(builder.bytes().utf8ToString(),
                is("{\"inputs\":[{\"first\":{\"simple\":{\"foo\":\"bar\"}}},{\"second\":{\"simple\":{\"spam\":\"eggs\"}}}]}"));

        // parsing it back as well!
        Map<String, InputFactory> factories = new HashMap<>();
        factories.put("simple", new SimpleInputFactory(Settings.EMPTY));

        InputRegistry inputRegistry = new InputRegistry(Settings.EMPTY, factories);
        ChainInputFactory chainInputFactory = new ChainInputFactory(Settings.EMPTY, inputRegistry);
        factories.put("chain", chainInputFactory);

        XContentParser parser = XContentFactory.xContent(builder.bytes()).createParser(builder.bytes());
        parser.nextToken();
        ChainInput parsedChainInput = ChainInput.parse("testWatchId", parser, inputRegistry);
        assertThat(parsedChainInput.getInputs(), hasSize(2));
        assertThat(parsedChainInput.getInputs().get(0).v1(), is("first"));
        assertThat(parsedChainInput.getInputs().get(0).v2(), is(instanceOf(SimpleInput.class)));
        assertThat(parsedChainInput.getInputs().get(1).v1(), is("second"));
        assertThat(parsedChainInput.getInputs().get(1).v2(), is(instanceOf(SimpleInput.class)));
    }

    public void testThatWatchSourceBuilderWorksWithChainInput() throws Exception {
        XContentBuilder builder = XContentFactory.contentBuilder(XContentType.JSON);

        HttpInput.Builder httpInputBuilder = httpInput(HttpRequestTemplate.builder("theHost", 1234)
                .path("/index/_search")
                .body(jsonBuilder().startObject().field("size", 1).endObject().string())
                .auth(new BasicAuth("test", "changeme".toCharArray())));

        ChainInput.Builder chainedInputBuilder = chainInput()
                .add("foo", httpInputBuilder)
                .add("bar", simpleInput("spam", "eggs"));

        watchBuilder()
                .trigger(schedule(interval("5s")))
                .input(chainedInputBuilder)
                .condition(new ScriptCondition(new Script("ctx.payload.hits.total == 1")))
                .addAction("_id", loggingAction("watch [{{ctx.watch_id}}] matched"))
                .toXContent(builder, ToXContent.EMPTY_PARAMS);

        // no exception means all good
    }

    public void testThatSerializationOfFailedInputWorks() throws Exception {
        ChainInput.Result chainedResult = new ChainInput.Result(new ElasticsearchException("foo"));

        XContentBuilder builder = jsonBuilder();
        chainedResult.toXContent(builder, ToXContent.EMPTY_PARAMS);
        assertThat(builder.bytes().utf8ToString(), containsString("\"reason\":\"ElasticsearchException[foo]\""));
    }

    private WatchExecutionContext createContext() {
        Watch watch = new Watch("test-watch",
                new ScheduleTrigger(new IntervalSchedule(new IntervalSchedule.Interval(1, IntervalSchedule.Interval.Unit.MINUTES))),
                new ExecutableSimpleInput(new SimpleInput(new Payload.Simple()), logger),
                AlwaysCondition.INSTANCE,
                null,
                null,
                new ArrayList<>(),
                null,
                new WatchStatus(new DateTime(0, UTC), emptyMap()));
        WatchExecutionContext ctx = new TriggeredExecutionContext(watch,
                new DateTime(0, UTC),
                new ScheduleTriggerEvent(watch.id(), new DateTime(0, UTC), new DateTime(0, UTC)),
                TimeValue.timeValueSeconds(5));

        return ctx;
    }

}
