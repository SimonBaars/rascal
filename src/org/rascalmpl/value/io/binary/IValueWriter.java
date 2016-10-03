/** 
 * Copyright (c) 2016, Davy Landman, Paul Klint, Centrum Wiskunde & Informatica (CWI) 
 * All rights reserved. 
 *  
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met: 
 *  
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer. 
 *  
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution. 
 *  
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. 
 */ 
package org.rascalmpl.value.io.binary;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;

import org.rascalmpl.interpreter.types.FunctionType;
import org.rascalmpl.interpreter.types.NonTerminalType;
import org.rascalmpl.interpreter.types.OverloadedFunctionType;
import org.rascalmpl.interpreter.types.ReifiedType;
import org.rascalmpl.value.IBool;
import org.rascalmpl.value.IConstructor;
import org.rascalmpl.value.IDateTime;
import org.rascalmpl.value.IExternalValue;
import org.rascalmpl.value.IInteger;
import org.rascalmpl.value.IList;
import org.rascalmpl.value.IMap;
import org.rascalmpl.value.INode;
import org.rascalmpl.value.IRational;
import org.rascalmpl.value.IReal;
import org.rascalmpl.value.ISet;
import org.rascalmpl.value.ISourceLocation;
import org.rascalmpl.value.IString;
import org.rascalmpl.value.ITuple;
import org.rascalmpl.value.IValue;
import org.rascalmpl.value.io.binary.util.OpenAddressingLastWritten;
import org.rascalmpl.value.io.binary.util.PrePostIValueIterator;
import org.rascalmpl.value.io.binary.util.TrackLastWritten;
import org.rascalmpl.value.type.ITypeVisitor;
import org.rascalmpl.value.type.Type;
import org.rascalmpl.value.visitors.IValueVisitor;
import org.rascalmpl.values.ValueFactoryFactory;

import com.github.luben.zstd.ZstdOutputStream;
        	
/**
 * A binary serializer for IValues and Types.
 * 
 * For most use cases, construct an instance of IValueWriter and write one or more IValues to it.
 * If you are also using the {@link ValueWriteOutputStream}, there is a static method write method inteded for this use case.
 * 
 * Note that when writing multiple IValues, you have to store this arity yourself.
 *
 */
public class IValueWriter implements Closeable {
    

    /**
     * Compression of the serializer, balances lookback windows and compression algorithm
     */
    public enum CompressionRate {
        /**
         * Use only for debugging!
         */
        None(10,0,0,0, 0),
        Fast(10, 10,10,10, 2),
        Normal(30, 30,100,50,0), // 7
        Extreme(50, 50,250,100, 9)
        ;

        private final int uriWindow;
        private final int typeWindow;
        private final int valueWindow;
        private final int stringsWindow;
        private final int compressionMode;

        CompressionRate(int stringsWindow, int typeWindow, int valueWindow, int uriWindow, int compressionMode) {
            this.stringsWindow = stringsWindow;
            this.typeWindow = typeWindow;
            this.valueWindow = valueWindow;
            this.uriWindow = uriWindow;
            this.compressionMode = compressionMode;
        } 
    }
    
	/*package*/ static final byte[] header = { 'R', 'V', 1,0,0 };
	
	/*package*/ static final class CompressionHeader {
	    public static final byte NONE = 0;
	    public static final byte GZIP = 1;
	    public static final byte XZ = 2;
	    public static final byte ZSTD = 3;
	}
	
	private static <T> TrackLastWritten<T> getWindow(int size) {
	    if (size == 0) {
	        return new TrackLastWritten<T>() {
	            public int howLongAgo(T obj) {
	                return -1;
	            }
	            public void write(T obj) {};
	        };
	    }
	    return OpenAddressingLastWritten.referenceEquality(size); 
	}

    private final ValueWireOutputStream writer;
    private final CompressionRate compression;
	
	public IValueWriter(OutputStream out, CompressionRate compression) throws IOException {
        out.write(header);
    	out.write(compression.typeWindow);
    	out.write(compression.valueWindow);
    	out.write(compression.uriWindow);
    	out.write(compression.compressionMode == 0 ? CompressionHeader.NONE : CompressionHeader.ZSTD);
    	if (compression.compressionMode > 0) {
    	    out = new ZstdOutputStream(out, compression.compressionMode);
    	}
    	writer = new ValueWireOutputStream(out, compression.stringsWindow * 1024);
    	this.compression = compression;
    }
	
	public void write(IValue value) throws IOException {
	    write(writer, compression.typeWindow * 1024, compression.valueWindow * 1024, compression.uriWindow * 1024, value);
	    writeEnd(writer);
	}


    @Override
    public void close() throws IOException {
       writer.close(); 
    }
	
	
    /**
     * In most cases you want to construct an instance of this class and call the normal write method, this method is only intended for embedded ValueWireOutputStreams
     * @param writer
     * @param typeWindowSize the size of the window for type-reuse. normally 1024 should be enough, when storing parse trees, use a larger number (10_000 for example)
     * @param valueWindowSize the size of the window for value-reuse. normally 100_000 should be enough, when expecting large values, you can use a larger number
     * @param uriWindowSize the size of the window for source location reuse. normally 50_000 should be more than enough, when you expect a lot of source locations, increase this number
     * @param value the value to write
     * @throws IOException
     */
	public static void write(ValueWireOutputStream writer, int typeWindowSize, int valueWindowSize, int uriWindowSize, IValue value ) throws IOException {
	    TrackLastWritten<Type> typeCache = getWindow(typeWindowSize);
	    TrackLastWritten<IValue> valueCache = getWindow(valueWindowSize);
	    TrackLastWritten<ISourceLocation> uriCache = getWindow(uriWindowSize);
	    write(writer, value, typeCache, valueCache, uriCache);
	    writeEnd(writer);
	}
	
	
	
	
	private static void writeNames(final ValueWireOutputStream writer, int fieldId, String[] names) throws IOException{
		writer.writeField(fieldId, names.length);
		for(int i = 0; i < names.length; i++){
		    writer.writeField(fieldId, names[i]);
		}
	}
	
	private static void write(final ValueWireOutputStream writer, final Type type, final TrackLastWritten<Type> typeCache, final TrackLastWritten<IValue> valueCache, final TrackLastWritten<ISourceLocation> uriCache) throws IOException {
	    type.accept(new ITypeVisitor<Void, IOException>() {

	        private boolean writeFromCache(Type type) throws IOException {
	            int lastSeen = typeCache.howLongAgo(type);
	            if (lastSeen != -1) { 
	                writeSingleValueMessage(writer, IValueIDs.PreviousType.ID, IValueIDs.PreviousType.HOW_LONG_AGO, lastSeen);
	                return true;
	            }
	            return false;
	        }

	        @Override
	        public Void visitAbstractData(Type type) throws IOException {
	            if (writeFromCache(type)) {
	                return null;
	            }
	            type.getTypeParameters().accept(this);
	            writeSingleValueMessageBackReferenced(writer, IValueIDs.ADTType.ID, IValueIDs.ADTType.NAME, type.getName());
	            typeCache.write(type);
	            return null;
	        }

	        @Override
	        public Void visitAlias(Type type) throws IOException {
	            if (writeFromCache(type)) {
	                return null;
	            }
	            type.getAliased().accept(this);
	            type.getTypeParameters().accept(this);
	            writeSingleValueMessageBackReferenced(writer, IValueIDs.AliasType.ID, IValueIDs.AliasType.NAME, type.getName());
	            typeCache.write(type);
	            return null;
	        }

	        @Override
	        public Void visitConstructor(Type type) throws IOException {
	            if (writeFromCache(type)) {
	                return null;
	            }
	            type.getAbstractDataType().accept(this);
	            type.getFieldTypes().accept(this);
	            writeSingleValueMessageBackReferenced(writer, IValueIDs.ConstructorType.ID, IValueIDs.ConstructorType.NAME, type.getName());
	            typeCache.write(type);
	            return null;
	        }

	        @Override
	        public Void visitExternal(Type type) throws IOException {
	            if (writeFromCache(type)) {
	                return null;
	            }
	            // TODO this should be here, but on the external type callback 
	            if(type instanceof FunctionType){
	                FunctionType ft = (FunctionType) type;
	                ft.getReturnType().accept(this);
	                ft.getArgumentTypes().accept(this);
	                ft.getKeywordParameterTypes().accept(this);
	                writeEmptyMessageBackReferenced(writer, IValueIDs.FunctionType.ID);
	            } else if(type instanceof ReifiedType){
	                type.getTypeParameters().accept(this);
	                writeEmptyMessageBackReferenced(writer,IValueIDs.ReifiedType.ID);
	            } else if(type instanceof OverloadedFunctionType){
	                Set<FunctionType> alternatives = ((OverloadedFunctionType) type).getAlternatives();
	                for(FunctionType ft : alternatives){
	                    ft.accept(this);
	                }
	                writeSingleValueMessageBackReferenced(writer, IValueIDs.OverloadedType.ID, IValueIDs.OverloadedType.SIZE, ((OverloadedFunctionType) type).getAlternatives().size());
	            } else if(type instanceof NonTerminalType){
	                write(writer, ((NonTerminalType)type).getSymbol(), typeCache, valueCache, uriCache);
	                writeEmptyMessageBackReferenced(writer, IValueIDs.NonTerminalType.ID);
	            } else {
	                throw new RuntimeException("External type not supported: " + type);
	            }
	            typeCache.write(type);
	            return null;
	        }

	        @Override
	        public Void visitList(Type type) throws IOException {
	            if (writeFromCache(type)) {
	                return null;
	            }
	            type.getElementType().accept(this);
	            writeEmptyMessageBackReferenced(writer, IValueIDs.ListType.ID);
	            typeCache.write(type);
	            return null;
	        }

	        @Override
	        public Void visitMap(Type type) throws IOException {
	            if (writeFromCache(type)) {
	                return null;
	            }
	            type.getKeyType().accept(this);
	            type.getValueType().accept(this);
	            writeEmptyMessageBackReferenced(writer, IValueIDs.MapType.ID);
	            typeCache.write(type);
	            return null;
	        }

	        @Override
	        public Void visitParameter(Type type) throws IOException {
	            if (writeFromCache(type)) {
	                return null;
	            }
	            type.getBound().accept(this);
	            writeSingleValueMessageBackReferenced(writer, IValueIDs.ParameterType.ID, IValueIDs.ParameterType.NAME,type.getName());
	            typeCache.write(type);
	            return null;
	        }

	        @Override
	        public Void visitSet(Type type) throws IOException {
	            if (writeFromCache(type)) {
	                return null;
	            }
	            type.getElementType().accept(this);
	            writeEmptyMessageBackReferenced(writer, IValueIDs.SetType.ID);
	            typeCache.write(type);
	            return null;
	        }

	        @Override
	        public Void visitTuple(Type type) throws IOException {
	            if (writeFromCache(type)) {
	                return null;
	            }
	            for(int i = 0; i < type.getArity(); i++){
	                type.getFieldType(i).accept(this);
	            }
	            writer.startMessage(IValueIDs.TupleType.ID);
	            writeCanBeBackReferenced(writer);
	            writer.writeField(IValueIDs.TupleType.ARITY, type.getArity());
	            String[] fieldNames = type.getFieldNames();
	            if(fieldNames != null){
	                writeNames(writer, IValueIDs.TupleType.NAMES, fieldNames);
	            }
	            writer.endMessage();
	            typeCache.write(type);
	            return null;
	        }

	        @Override
	        public Void visitBool(Type t) throws IOException {
	            writer.writeEmptyMessage(IValueIDs.BoolType.ID);
	            return null;
	        }

	        @Override
	        public Void visitDateTime(Type t) throws IOException {
	            writer.writeEmptyMessage(IValueIDs.DateTimeType.ID);
	            return null;
	        }

	        @Override
	        public Void visitInteger(Type t) throws IOException {
	            writer.writeEmptyMessage(IValueIDs.IntegerType.ID);
	            return null;
	        }

	        @Override
	        public Void visitNode(Type t) throws IOException {
	            writer.writeEmptyMessage(IValueIDs.NodeType.ID);
	            return null;
	        }

	        @Override
	        public Void visitNumber(Type t) throws IOException {
	            writer.writeEmptyMessage(IValueIDs.NumberType.ID);
	            return null;
	        }

	        @Override
	        public Void visitRational(Type t) throws IOException {
	            writer.writeEmptyMessage(IValueIDs.RationalType.ID);
	            return null;
	        }

	        @Override
	        public Void visitReal(Type t) throws IOException {
	            writer.writeEmptyMessage(IValueIDs.RealType.ID);
	            return null;
	        }

	        @Override
	        public Void visitSourceLocation(Type t) throws IOException {
	            writer.writeEmptyMessage(IValueIDs.SourceLocationType.ID);
	            return null;
	        }

	        @Override
	        public Void visitString(Type t) throws IOException {
	            writer.writeEmptyMessage(IValueIDs.StringType.ID);
	            return null;
	        }

	        @Override
	        public Void visitValue(Type t) throws IOException {
	            writer.writeEmptyMessage(IValueIDs.ValueType.ID);
	            return null;
	        }

	        @Override
	        public Void visitVoid(Type t) throws IOException {
	            writer.writeEmptyMessage(IValueIDs.VoidType.ID);
	            return null;
	        }
	    });
	}
	
	private static void writeSingleValueMessage(final ValueWireOutputStream writer, int messageID, int fieldId, long fieldValue) throws IOException {
	    writer.startMessage(messageID);
	    writer.writeField(fieldId, fieldValue);
	    writer.endMessage();
	}
	private static void writeSingleValueMessageBackReferenced(final ValueWireOutputStream writer, int messageID, int fieldId, long fieldValue) throws IOException {
	    writer.startMessage(messageID);
	    writeCanBeBackReferenced(writer);
	    writer.writeField(fieldId, fieldValue);
	    writer.endMessage();
	}
	
	private static void writeSingleValueMessage(final ValueWireOutputStream writer, int messageID, int fieldId, String fieldValue) throws IOException {
	    writer.startMessage(messageID);
	    writer.writeField(fieldId, fieldValue);
	    writer.endMessage();
	}
	private static void writeSingleValueMessageBackReferenced(final ValueWireOutputStream writer, int messageID, int fieldId, String fieldValue) throws IOException {
	    writer.startMessage(messageID);
	    writeCanBeBackReferenced(writer);
	    writer.writeField(fieldId, fieldValue);
	    writer.endMessage();
	}
	private static void writeEmptyMessageBackReferenced(final ValueWireOutputStream writer, int messageID) throws IOException {
	    writer.startMessage(messageID);
	    writeCanBeBackReferenced(writer);
	    writer.endMessage();
	}
	private static void writeCanBeBackReferenced(final ValueWireOutputStream writer) throws IOException {
	    writer.writeField(IValueIDs.Common.CAN_BE_BACK_REFERENCED, 1);
	}
    private static void writeEnd(ValueWireOutputStream writer) throws IOException {
        writer.writeEmptyMessage(IValueIDs.LastValue.ID);
    }

	private static final IInteger MININT =ValueFactoryFactory.getValueFactory().integer(Integer.MIN_VALUE);
	private static final IInteger MAXINT =ValueFactoryFactory.getValueFactory().integer(Integer.MAX_VALUE);
	
	private static void write(final ValueWireOutputStream writer, final IValue value, final TrackLastWritten<Type> typeCache, final TrackLastWritten<IValue> valueCache, final TrackLastWritten<ISourceLocation> uriCache) throws IOException {
		PrePostIValueIterator iter = new PrePostIValueIterator(value);
		
		// returns if the value should be put into the cache or not
		IValueVisitor<Boolean, IOException> visitWriter = new IValueVisitor<Boolean, IOException>() {

		    private boolean writeFromCache(IValue val) throws IOException {
		        int lastSeen = valueCache.howLongAgo(val);
		        if (lastSeen != -1) {
		            writeSingleValueMessage(writer, IValueIDs.PreviousValue.ID, IValueIDs.PreviousValue.HOW_FAR_BACK, lastSeen);
		            iter.skipValue();
		            return true;
		        }
		        return false;
		    }
		    @Override
		    public Boolean visitConstructor(IConstructor cons) throws IOException {
		        if (writeFromCache(cons) || iter.atBeginning()) {
		            return false;
		        }
		        write(writer, cons.getUninstantiatedConstructorType(), typeCache, valueCache, uriCache);

		        writer.startMessage(IValueIDs.ConstructorValue.ID);
		        writeCanBeBackReferenced(writer);
		        writer.writeField(IValueIDs.ConstructorValue.ARITY, cons.arity());
		        if(cons.mayHaveKeywordParameters()){
		            if(cons.asWithKeywordParameters().hasParameters()){
		                writer.writeField(IValueIDs.ConstructorValue.KWPARAMS, cons.asWithKeywordParameters().getParameters().size());
		            }
		        } else {
		            if(cons.asAnnotatable().hasAnnotations()){
		                writer.writeField(IValueIDs.ConstructorValue.ANNOS, cons.asAnnotatable().getAnnotations().size());
		            }
		        }
		        writer.endMessage();
		        return true;
		    }
		    @Override
		    public Boolean visitNode(INode node) throws IOException {
		        if (writeFromCache(node) || iter.atBeginning()) {
		            return false;
		        }
		        writer.startMessage(IValueIDs.NodeValue.ID);
		        writeCanBeBackReferenced(writer);
		        writer.writeField(IValueIDs.NodeValue.NAME,  node.getName());
		        writer.writeField(IValueIDs.NodeValue.ARITY, node.arity());
		        if(node.mayHaveKeywordParameters()){
		            if(node.asWithKeywordParameters().hasParameters()){
		                writer.writeField(IValueIDs.NodeValue.KWPARAMS, node.asWithKeywordParameters().getParameters().size());
		            }
		        } else {
		            if(node.asAnnotatable().hasAnnotations()){
		                writer.writeField(IValueIDs.NodeValue.ANNOS, node.asAnnotatable().getAnnotations().size());
		            }
		        }
		        writer.endMessage();
		        return true;
		    }
		    @Override
		    public Boolean visitList(IList o) throws IOException {
		        if (writeFromCache(o) || iter.atBeginning()) {
		            return false;
		        }
		        writeSingleValueMessageBackReferenced(writer, IValueIDs.ListValue.ID, IValueIDs.ListValue.SIZE, o.length());
		        return true;
		    }
		    @Override
		    public Boolean visitMap(IMap o) throws IOException {
		        if (writeFromCache(o) || iter.atBeginning()) {
		            return false;
		        }
		        writeSingleValueMessageBackReferenced(writer, IValueIDs.MapValue.ID, IValueIDs.MapValue.SIZE, o.size());
		        return true;
		    }
		    @Override
		    public Boolean visitSet(ISet o) throws IOException {
		        if (writeFromCache(o) || iter.atBeginning()) {
		            return false;
		        }
		        writeSingleValueMessageBackReferenced(writer, IValueIDs.SetValue.ID, IValueIDs.SetValue.SIZE, o.size());
		        return true;
		    }
		    @Override
		    public Boolean visitRational(IRational o) throws IOException {
		        if (writeFromCache(o) || iter.atBeginning()) {
		            return false;
		        }
		        writeEmptyMessageBackReferenced(writer, IValueIDs.RationalValue.ID);
		        return true;
		    }
		    @Override
		    public Boolean visitTuple(ITuple o) throws IOException {
		        if (writeFromCache(o) || iter.atBeginning()) {
		            return false;
		        }
			     writeSingleValueMessageBackReferenced(writer, IValueIDs.TupleValue.ID, IValueIDs.TupleValue.SIZE, o.arity());
			     return true;
		    }

		    @Override
		    public Boolean visitBoolean(IBool boolValue) throws IOException {
		        writeSingleValueMessage(writer, IValueIDs.BoolValue.ID, IValueIDs.BoolValue.VALUE, boolValue.getValue() ? 1: 0);
		        return false;
		    }

		    @Override
		    public Boolean visitDateTime(IDateTime dateTime) throws IOException {
		        writer.startMessage(IValueIDs.DateTimeValue.ID);

		        if (!dateTime.isTime()) {
		            writer.writeField(IValueIDs.DateTimeValue.YEAR, dateTime.getYear());
		            writer.writeField(IValueIDs.DateTimeValue.MONTH, dateTime.getMonthOfYear());
		            writer.writeField(IValueIDs.DateTimeValue.DAY, dateTime.getDayOfMonth());
		        }

		        if (!dateTime.isDate()) {
		            writer.writeField(IValueIDs.DateTimeValue.HOUR, dateTime.getHourOfDay());
		            writer.writeField(IValueIDs.DateTimeValue.MINUTE, dateTime.getMinuteOfHour());
		            writer.writeField(IValueIDs.DateTimeValue.SECOND, dateTime.getSecondOfMinute());
		            writer.writeField(IValueIDs.DateTimeValue.MILLISECOND, dateTime.getMillisecondsOfSecond());

		            writer.writeField(IValueIDs.DateTimeValue.TZ_HOUR, dateTime.getTimezoneOffsetHours());
		            writer.writeField(IValueIDs.DateTimeValue.TZ_MINUTE, dateTime.getTimezoneOffsetMinutes());
		        }
		        writer.endMessage();
		        return false;
		    }
		    @Override
		    public Boolean visitInteger(IInteger ii) throws IOException {
		        writer.startMessage(IValueIDs.IntegerValue.ID);
		        if(ii. greaterEqual(MININT).getValue() && ii.lessEqual(MAXINT).getValue()){
		            writer.writeField(IValueIDs.IntegerValue.INTVALUE, ii.intValue());
		        } 
		        else {
		            writer.writeField(IValueIDs.IntegerValue.BIGVALUE, ii.getTwosComplementRepresentation());
		        }
		        writer.endMessage();
		        return false;
		    }


		    @Override
		    public Boolean visitReal(IReal o) throws IOException {
		        writer.startMessage(IValueIDs.RealValue.ID);
		        writer.writeField(IValueIDs.RealValue.CONTENT, o.unscaled().getTwosComplementRepresentation());
		        writer.writeField(IValueIDs.RealValue.SCALE, o.scale());
		        writer.endMessage();
		        return false;
		    }

		    @Override
		    public Boolean visitSourceLocation(ISourceLocation loc) throws IOException {
		        writer.startMessage(IValueIDs.SourceLocationValue.ID);
		        ISourceLocation uriPart = loc.top();
		        int alreadyWritten = uriCache.howLongAgo(uriPart);
		        if (alreadyWritten == -1) {
		            writer.writeField(IValueIDs.SourceLocationValue.SCHEME, uriPart.getScheme());
		            if (uriPart.hasAuthority()) {
		                writer.writeField(IValueIDs.SourceLocationValue.AUTHORITY, uriPart.getAuthority());
		            }
		            if (uriPart.hasPath()) {
		                writer.writeField(IValueIDs.SourceLocationValue.PATH, uriPart.getPath());
		            }
		            if (uriPart.hasQuery()) {
		                writer.writeField(IValueIDs.SourceLocationValue.QUERY,  uriPart.getQuery());
		            }
		            if (uriPart.hasFragment()) {
		                writer.writeField(IValueIDs.SourceLocationValue.FRAGMENT,  uriPart.getFragment());
		            }
		            uriCache.write(uriPart);
		        }
		        else {
		            writer.writeField(IValueIDs.SourceLocationValue.PREVIOUS_URI, alreadyWritten);
		        }

		        if(loc.hasOffsetLength()){
		            writer.writeField(IValueIDs.SourceLocationValue.OFFSET, loc.getOffset());
		            writer.writeField(IValueIDs.SourceLocationValue.LENGTH, loc.getLength());
		        } 
		        if(loc.hasLineColumn()){
		            writer.writeField(IValueIDs.SourceLocationValue.BEGINLINE, loc.getBeginLine());
		            writer.writeField(IValueIDs.SourceLocationValue.ENDLINE, loc.getEndLine());
		            writer.writeField(IValueIDs.SourceLocationValue.BEGINCOLUMN, loc.getBeginColumn());
		            writer.writeField(IValueIDs.SourceLocationValue.ENDCOLUMN, loc.getEndColumn());
		        }
		        writer.endMessage();
		        return false;
		    }

		    @Override
		    public Boolean visitString(IString o) throws IOException {
		        writeSingleValueMessage(writer, IValueIDs.StringValue.ID, IValueIDs.StringValue.CONTENT, o.getValue());
		        return false;
		    }
		    @Override
		    public Boolean visitExternal(IExternalValue externalValue) throws IOException {
		        throw new RuntimeException("Not supported yet");
		    }
		    @Override
		    public Boolean visitListRelation(IList o) throws IOException {
		        return visitList(o);
		    }
		    @Override
		    public Boolean visitRelation(ISet o) throws IOException {
		        return visitSet(o);
		    }
		};

		while(iter.hasNext()){
		    final IValue currentValue = iter.next();
		    if (currentValue.accept(visitWriter)) {
		        valueCache.write(currentValue);
		    }
		}
	}


}
