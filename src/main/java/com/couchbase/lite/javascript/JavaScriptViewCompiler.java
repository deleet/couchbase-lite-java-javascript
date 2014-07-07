package com.couchbase.lite.javascript;

import com.couchbase.lite.Database;
import com.couchbase.lite.Emitter;
import com.couchbase.lite.Mapper;
import com.couchbase.lite.Reducer;
import com.couchbase.lite.ViewCompiler;
import com.couchbase.lite.util.Log;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.WrapFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JavaScriptViewCompiler implements ViewCompiler {

	@Override
	public Mapper compileMap(String source, String language) {
        if (language.equalsIgnoreCase("javascript")) {
            return new ViewMapBlockRhino(source);
        }

        throw new IllegalArgumentException(language + " is not supported");
	}

	@Override
	public Reducer compileReduce(String source, String language) {
        if (language.equalsIgnoreCase("javascript")) {
            return new ViewReduceBlockRhino(source);
        }

        throw new IllegalArgumentException(language + " is not supported");
	}
}

// REFACT: Extract superview for both the map and reduce blocks as they do pretty much the same thing

class ViewMapBlockRhino implements Mapper {

    private final String mapSrc;
    private final Scriptable globalScope;
    private final Script placeHolder;

    private final WrapFactory wrapFactory;
    private static final ObjectMapper mapper = new ObjectMapper();

    public ViewMapBlockRhino(String src) {
        mapSrc = src;

        mapper.getFactory().enable(JsonGenerator.Feature.ESCAPE_NON_ASCII);

        final Context ctx = Context.enter();

        // Android dex won't allow us to create our own classes
        ctx.setOptimizationLevel(-1);

        globalScope = ctx.initStandardObjects(null, true);
        wrapFactory = new CustomWrapFactory(globalScope);

        ctx.setWrapFactory(wrapFactory);


        try {
            // create a place to hold results
            final String resultArray = "var map_results = [];";
            placeHolder = ctx.compileString(resultArray, "placeHolder", 1, null);

            try {
                //register the emit function
                final String emitFunction = "var emit = function(key, value) { map_results.push([key, value]); };";
                ctx.evaluateString(globalScope, emitFunction, "emit", 1, null);

                // register the map function
                final String map = "var map = " + mapSrc + ";";
                ctx.evaluateString(globalScope, map, "map", 1, null);
            } catch(org.mozilla.javascript.EvaluatorException e) {
                // Error in the JavaScript view - CouchDB swallows  the error and tries the next document
                Log.e(Database.TAG, "Javascript syntax error in view:\n" + src, e);
                return;
            }
        } finally {
            Context.exit();
        }
    }

	@Override
    public void map(Map<String, Object> document, Emitter emitter) {
        Context ctx = Context.enter();
        try {
            ctx.setOptimizationLevel(-1);
            ctx.setWrapFactory(wrapFactory);

            // empty out the array that may have been filled by a previous call of this method
            ctx.executeScriptWithContinuations(placeHolder, globalScope);
            
            // Need to stringify the json tree, as the ContextWrapper is unable
            // to correctly convert nested json to their js representation.
            // More specifically, if a dictionary is included that contains an array as a value 
            // that array will not be wrapped correctly but you'll get the plain 
            // java.util.ArrayList instead - and then an error.
            try {
                // One thing that CouchDB does is replace these whitespace/newlines values with null-bytes
                final String json = mapper.writeValueAsString(document).replace("\\u2028", "\0");
                final String mapInvocation = "map(" + json + ");";

                ctx.evaluateString(globalScope, mapInvocation, "map invocation", 1, null);
			} catch (org.mozilla.javascript.RhinoException e) {
                // Error in the JavaScript view - CouchDB swallows  the error and tries the next document
                Log.e(Database.TAG, "Error in javascript view:\n" + mapSrc + "\n with document:\n" + document, e);
                return;
            } catch (IOException e) {
				// Can thrown different subclasses of IOException- but we really do not care,
				// as this document was unserialized from JSON, so Jackson should be able to serialize it. 
				Log.e(Database.TAG, "Error reserializing json from the db: " + document, e);
				return;
			}

            //now pull values out of the place holder and emit them
            final NativeArray mapResults = (NativeArray) globalScope.get("map_results", globalScope);

            final int resultSize = (int) mapResults.getLength();

            for (int i=0; i< resultSize; i++) {
                final NativeArray mapResultItem = (NativeArray) mapResults.get(i);

                if (mapResultItem != null && mapResultItem.getLength() == 2) {
                    Object key = mapResultItem.get(0);
                    Object value = mapResultItem.get(1);
                    emitter.emit(key, value);
                } else {
                    Log.e(Database.TAG, "Expected 2 element array with key and value");
                }
            }
        } finally {
            Context.exit();
        }
    }
}

class ViewReduceBlockRhino implements Reducer {

    private final Scriptable globalScope;
    private final WrapFactory wrapFactory;
    private final String source;
    private boolean isBuiltIn = false;

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final List<String> builtIn = new ArrayList<String>() {{
        add("_sum");
        add("_count");
        add("_stats");
    }};

    public ViewReduceBlockRhino(String src) {
        final Context ctx = Context.enter();

        // Android dex won't allow us to create our own classes
        ctx.setOptimizationLevel(-1);

        mapper.getFactory().enable(JsonGenerator.Feature.ESCAPE_NON_ASCII);

        globalScope = ctx.initStandardObjects(null, true);
        wrapFactory = new CustomWrapFactory(globalScope);
        source = src;

        ctx.setWrapFactory(wrapFactory);

        try {
            if (!builtIn.contains(src)) {
                // register the reduce function
                final String reduceSrc = "var reduce = " + src + ";";
                ctx.evaluateString(globalScope, reduceSrc, "reduce", 1, null);
            } else {
                isBuiltIn = true;
            }
        } finally {
            Context.exit();
        }
    }

	@Override
    public Object reduce(List<Object> keys, List<Object> values, boolean rereduce) {
        Context ctx = Context.enter();
        try {
            ctx.setOptimizationLevel(-1);
            ctx.setWrapFactory(wrapFactory);

            if (isBuiltIn) {
                if (source.equalsIgnoreCase("_sum")) return sum(keys, values, rereduce);
                else if (source.equalsIgnoreCase("_count")) return count(keys, values, rereduce);
                else if (source.equalsIgnoreCase("_stats")) return stats(keys, values, rereduce);
            } else {
                // find the reduce function and execute it
                Function reduceFun = (Function) globalScope.get("reduce", globalScope);
                Object[] functionArgs = {keys, values, rereduce};

                return reduceFun.call(ctx, globalScope, globalScope, functionArgs);
            }
        } catch (Exception e) {
            Log.e(Database.TAG, "Error while executing reduce function: " + source, e);
        } finally {
            Context.exit();
        }

        return null;
    }

    protected Object sum(List<Object> keys, List<Object> values, boolean rereduce) throws Exception {
        // not really sure?
        return values.size();
    }

    protected Object count(List<Object> keys, List<Object> values, boolean rereduce) throws Exception {
        return (rereduce) ? sum(keys, values, rereduce) : values.size();
    }

    protected Object stats(List<Object> keys, List<Object> values, boolean rereduce) throws Exception {
        final Map<String, Object> props = new HashMap<String, Object>();

        props.put("sum", sum(keys, values, rereduce));
        props.put("count", count(keys, values, rereduce));
        props.put("min", 0);
        props.put("max", 1);
        props.put("sumsqr", 0);

        return mapper.writeValueAsString(props);
    }
}
