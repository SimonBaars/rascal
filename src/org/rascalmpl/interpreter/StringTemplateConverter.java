/*******************************************************************************
 * Copyright (c) 2009-2013 CWI
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:

 *   * Jurgen J. Vinju - Jurgen.Vinju@cwi.nl - CWI
 *   * Tijs van der Storm - Tijs.van.der.Storm@cwi.nl
 *   * Mark Hills - Mark.Hills@cwi.nl (CWI)
 *   * Arnold Lankamp - Arnold.Lankamp@cwi.nl
*******************************************************************************/
package org.rascalmpl.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import org.rascalmpl.ast.DataTarget;
import org.rascalmpl.ast.Expression;
import org.rascalmpl.ast.Indentation;
import org.rascalmpl.ast.Name;
import org.rascalmpl.ast.NullASTVisitor;
import org.rascalmpl.ast.Statement;
import org.rascalmpl.ast.StringLiteral.Default;
import org.rascalmpl.ast.StringPart;
import org.rascalmpl.ast.StringPart.Block;
import org.rascalmpl.ast.StringPart.Comp;
import org.rascalmpl.ast.StringPart.Expr;
import org.rascalmpl.ast.StringPart.For2;
import org.rascalmpl.ast.StringPart.Forsep;
import org.rascalmpl.ast.StringPart.Hole;
import org.rascalmpl.ast.StringPart.IfThen2;
import org.rascalmpl.ast.StringPart.IfThenElse2;
import org.rascalmpl.ast.StringPart.Margin;
import org.rascalmpl.ast.StringPart.Sepcomp;
import org.rascalmpl.ast.StringPart.Var;
import org.rascalmpl.ast.StringPart.While2;
import org.rascalmpl.interpreter.result.Result;
import org.rascalmpl.interpreter.utils.Names;
import org.rascalmpl.parser.ASTBuilder;
import org.rascalmpl.value.IConstructor;
import org.rascalmpl.value.ISourceLocation;
import org.rascalmpl.value.IString;
import org.rascalmpl.value.IValue;
import org.rascalmpl.values.uptr.RascalValueFactory;
import org.rascalmpl.values.uptr.SymbolAdapter;
import org.rascalmpl.values.uptr.TreeAdapter;
  
public class StringTemplateConverter {
	private static int labelCounter = 0;
	
	private static class ConstAppend extends org.rascalmpl.semantics.dynamic.Statement.Append {
		protected final String str;
		protected final String indentation;
	
		public ConstAppend(ISourceLocation src, DataTarget target, String arg, String indentation) {
			super(src, null,  target, null);
			str = arg;
			this.indentation = indentation;
		}
		
		@Override
		public Result<IValue> interpret(IEvaluator<Result<IValue>> eval) {
			Accumulator target = getTarget(eval);
			
			for (String line : str.toString().split("$")) {
				target.appendString(indentation);
				target.appendString(line);
			}
			
			return null;
		}
	}

	/**
	 * This is a new Rascal statement invented here on the fly to indent a string which is dynamically
	 * generated by code. The idea is to split the string on newlines and add the indent which is statically
	 * computed from the template's shape.
	 */
	private static class IndentingAppend extends org.rascalmpl.semantics.dynamic.Statement.Append {
		private final String indent;
		
		public IndentingAppend(ISourceLocation src,  DataTarget target, Statement __param3, String indent) {
			super(src, null, target, __param3);
			this.indent = indent;
		} 
		
		@Override
		public Result<IValue> interpret(IEvaluator<Result<IValue>> __eval) {
			Accumulator target = getTarget(__eval);
			Result<IValue> result;
			
			if (__eval.getCurrentEnvt().getVariable(Names.toName("format", getLocation())) == null) {
				// no format function in scope, reverting to the argument of the call
				result = makeStatExpr(getStatement().getExpression().getArguments().get(0)).interpret(__eval);
			}
			else {
				result = this.getStatement().interpret(__eval);
			}
			
			String content = toString(result.getValue());
			
			target.appendString(indent);
			
			for (int i = 0; i < content.length(); i++) {
				int ch = content.charAt(i);
				target.appendCodePoint(ch);
				if (ch == '\n') {
					target.appendString(indent);
				}
			}

			return result;
		}
		
		private String toString(IValue value) {
			if (value.getType().isSubtypeOf(RascalValueFactory.Tree)) {
				return TreeAdapter.yield((IConstructor) value);
			}
			else if (value.getType().isSubtypeOf(RascalValueFactory.Type)) {
				return SymbolAdapter.toString((IConstructor) ((IConstructor) value).get("symbol"), false);
			}
			else if (value.getType().isString()) {
				return (((IString) value).getValue());
			} 
			else {
				return value.toString();
			}
		}
	}

	private static Statement surroundWithSingleIterForLoop(ISourceLocation loc, Name label, List<Statement> stats) {
		Name dummy = ASTBuilder.make("Name","Lexical",loc, "_");
		Expression var = ASTBuilder.make("Expression","QualifiedName",loc, ASTBuilder.make("QualifiedName", loc, Arrays.asList(dummy)));
		Expression truth = ASTBuilder.make("Expression","Literal",loc, ASTBuilder.make("Literal","Boolean",loc, ASTBuilder.make("BooleanLiteral","Lexical",loc, "true")));
		Expression list = ASTBuilder.make("Expression","List", loc, Arrays.asList(truth));
		Expression enumerator = ASTBuilder.make("Expression","Enumerator",loc, var, list);
		Statement body = makeBlock(loc, stats);
		Statement stat = ASTBuilder.make("Statement","For",loc, ASTBuilder.make("Label","Default", loc, label), Arrays.asList(enumerator), body);
		return stat;
	}

	public Statement convert(org.rascalmpl.ast.StringLiteral str) {
		final Name label= ASTBuilder.make("Name","Lexical", str.getLocation(), "#" + labelCounter);
		labelCounter++;
		List<Statement> stat = str.accept(new Visitor(label));
		return surroundWithSingleIterForLoop(str.getLocation(), label, stat);
	}
	
	private static Statement makeStatExpr(Expression exp) {
		return ASTBuilder.makeStat("Expression", exp.getLocation(), exp);
	}
	
	private static Statement makeBlock(ISourceLocation loc, List<Statement> stats) {
		return ASTBuilder.make("Statement","NonEmptyBlock",loc, ASTBuilder.make("Label", "Empty", loc), stats);
	}
	
	private static class Visitor extends NullASTVisitor<List<Statement>> {
		private final Name label;
		String indentation;
		
		public Visitor(Name label) {
			this.label = label;
		}
		
		private DataTarget makeTarget(ISourceLocation loc) {
			return ASTBuilder.<DataTarget>make("DataTarget","Labeled", loc, label);
		}
		
		String indent(Indentation indent) {
			return ((IString) indent.interpret(null).getValue()).getValue();
		}
		
		@Override
		public List<Statement> visitStringLiteralDefault(Default x) {
			indentation = indent(x.getIndent());
			List<StringPart> b = x.getBody();
			
			List<Statement> result = new LinkedList<>();
			result.add(new ConstAppend(x.getLocation(), makeTarget(x.getLocation()), "", indentation));
			result.addAll(body(b));
			return result;
		}
		
		private List<Statement> body(List<StringPart> body) {
			List<Statement> result = new LinkedList<>();
			
			int i = 0;
			for (StringPart part : body) {
				result.addAll(part.accept(this));
				
				// normally indentation is collected to be appended later to the next element, 
				// but if its the last part we need to handle the corner case here where there is no following element
				if (i++ == body.size() - 1 && part.isMargin()) {
					result.add(new ConstAppend(part.getLocation(), makeTarget(part.getLocation()), "", indentation));
				}
			}
			
			return result;
		}

		@Override
		public List<Statement> visitStringPartDoWhile(org.rascalmpl.ast.StringPart.DoWhile x) {
			List<Statement> stats = new ArrayList<Statement>();
			
			stats.addAll(x.getPreStats());
			stats.addAll(body(x.getBody()));
			stats.addAll(x.getPostStats());
			
			return single(ASTBuilder.makeStat("DoWhile", x.getLocation(), ASTBuilder.make("Label","Empty", x.getLocation()), 
					makeBlock(x.getLocation(), stats) , x.getCondition()));
		}
		
		public List<Statement> visitStringPartFor(org.rascalmpl.ast.StringPart.For x) {
			List<Statement> stats = new ArrayList<Statement>();
			
			stats.addAll(x.getPreStats());
			stats.addAll(body(x.getBody()));
			stats.addAll(x.getPostStats());
			
			return single(ASTBuilder.makeStat("For", x.getLocation(), ASTBuilder.make("Label","Empty", x.getLocation()), x.getGenerators(), 
					makeBlock(x.getLocation(), stats)));
		};
		
		private List<Statement> single(Statement s) {
			return Arrays.asList(new Statement[] { s });
		}
		
		private List<Expression> single(Expression s) {
			return Arrays.asList(new Expression[] { s });
		}
		
		
		@Override
		public List<Statement> visitStringPartMargin(Margin x) {
			// a margin sets the new indentation level
			indentation = indent(x.getIndent());
			// otherwise the margin is ignored and we add a newline
			return single(new ConstAppend(x.getLocation(), makeTarget(x.getLocation()), "\n", ""));
		}
		
		@Override
		public List<Statement> visitStringPartHole(Hole x) {
			Expression call = ASTBuilder.makeExp("CallOrTree", x.getLocation(), ASTBuilder.makeExp("QualifiedName", x.getLocation(), Names.toQualifiedName("format", x.getLocation())), single(x.getArg()), x.getKeywordArguments());
			return single(new IndentingAppend(x.getLocation(), makeTarget(x.getLocation()), makeStatExpr(call), indentation));
		}
		
		@Override
		public List<Statement> visitStringPartVar(Var x) {
			Expression call = ASTBuilder.makeExp("CallOrTree", x.getLocation(), ASTBuilder.makeExp("QualifiedName", x.getLocation(), Names.toQualifiedName("format", x.getLocation())), single(ASTBuilder.makeExp("QualifiedName", x.getLocation(), x.getVariable())), ASTBuilder.make("KeywordArguments_Expression", "None", x.getLocation()));
			return single(new IndentingAppend(x.getLocation(), makeTarget(x.getLocation()), makeStatExpr(call), indentation));	
		}
		
		@Override
		public List<Statement> visitStringPartExpr(Expr x) {
			Expression call = ASTBuilder.makeExp("CallOrTree", x.getLocation(), ASTBuilder.makeExp("QualifiedName", x.getLocation(), Names.toQualifiedName("format", x.getLocation())), single(x.getResult()), x.getKeywordArguments());
			return single(new IndentingAppend(x.getLocation(), makeTarget(x.getLocation()), makeStatExpr(call), indentation));
		}
		
		@Override
		public List<Statement> visitStringPartBlock(Block x) {
			return x.getStatements();
		}
		
		@Override
		public List<Statement> visitStringPartComp(Comp x) {
			List<Statement> stats = new ArrayList<Statement>();
			
			Expression call = ASTBuilder.makeExp("CallOrTree", x.getLocation(), ASTBuilder.makeExp("QualifiedName", x.getLocation(), Names.toQualifiedName("format", x.getLocation())), single(x.getResult()), x.getKeywordArguments());
			stats.add(makeStatExpr(call));
			
			return single(ASTBuilder.makeStat("For", x.getLocation(), ASTBuilder.make("Label","Empty", x.getLocation()), x.getGenerators(), 
					makeBlock(x.getLocation(), stats)));
		}
		
		@Override
		public List<Statement> visitStringPartFor2(For2 x) {
			List<Statement> stats = new ArrayList<Statement>();
			stats.addAll(body(x.getBody()));
			return single(ASTBuilder.makeStat("For", x.getLocation(), ASTBuilder.make("Label","Empty", x.getLocation()), x.getConditions(), 
					makeBlock(x.getLocation(), stats)));
		}
		
		@Override
		public List<Statement> visitStringPartForsep(Forsep x) {
			List<Statement> stats = new ArrayList<Statement>();
			
			stats.addAll(body(x.getBody()));
			
			// TODO: this one has to be conditional; only when a next element will be printed
			// or undone.
			stats.addAll(body(x.getSepBody()));
			
			return single(ASTBuilder.makeStat("For", x.getLocation(), ASTBuilder.make("Label","Empty", x.getLocation()), x.getConditions(), 
					makeBlock(x.getLocation(), stats)));
		}
		
		@Override
		public List<Statement> visitStringPartIfThen2(IfThen2 x) {
			List<Statement> stats = new ArrayList<Statement>();
			stats.addAll(body(x.getBody()));
			return single(ASTBuilder.makeStat("IfThen", x.getLocation(), ASTBuilder.make("Label", "Empty", x.getLocation()), x.getConditions(), 
					makeBlock(x.getLocation(), stats)));
		}
		
		@Override
		public List<Statement> visitStringPartIfThenElse2(IfThenElse2 x) {
			List<Statement> stats = new ArrayList<>();
			stats.addAll(body(x.getBody()));
			
			List<Statement> elseStats = new ArrayList<>();
			stats.addAll(body(x.getElseBody()));

			return single(ASTBuilder.makeStat("IfThenElse", x.getLocation(), ASTBuilder.make("Label","Empty",x.getLocation()), 
					x.getConditions(), makeBlock(x.getLocation(), stats), makeBlock(x.getLocation(), elseStats)));
		}
		
		@Override
		public List<Statement> visitStringPartWhile2(While2 x) {
			List<Statement> stats = new ArrayList<Statement>();
			stats.addAll(body(x.getBody()));
			
			return single(ASTBuilder.makeStat("While", x.getLocation(), ASTBuilder.make("Label","Empty", x.getLocation()), Collections.singletonList(x.getCondition()), 
					makeBlock(x.getLocation(), stats)));
		}
		
		@Override
		public List<Statement> visitStringPartSepcomp(Sepcomp x) {
			List<Statement> stats = new ArrayList<Statement>();
			
			Expression call = ASTBuilder.makeExp("CallOrTree", x.getLocation(), ASTBuilder.makeExp("QualifiedName", x.getLocation(), Names.toQualifiedName("format", x.getLocation())), single(x.getResult()), x.getKeywordArguments());
			stats.add(makeStatExpr(call));
			
			// TODO: this has to become conditional
			Expression sepcall = ASTBuilder.makeExp("CallOrTree", x.getLocation(), ASTBuilder.makeExp("QualifiedName", x.getLocation(), Names.toQualifiedName("format", x.getLocation())), single(x.getSep()), x.getKeywordArguments());
			stats.add(makeStatExpr(sepcall));
			
			return single(ASTBuilder.makeStat("For", x.getLocation(), ASTBuilder.make("Label","Empty", x.getLocation()), x.getGenerators(), 
					makeBlock(x.getLocation(), stats)));
		}
		
		@Override
		public List<Statement> visitStringPartIfThen(org.rascalmpl.ast.StringPart.IfThen x) {
			List<Statement> stats = new ArrayList<Statement>();
			stats.addAll(x.getPreStats());
			stats.addAll(body(x.getBody()));
			stats.addAll(x.getPostStats());

			return single(ASTBuilder.makeStat("IfThen", x.getLocation(), ASTBuilder.make("Label", "Empty", x.getLocation()), x.getConditions(), 
					makeBlock(x.getLocation(), stats)));
		}
		
		@Override
		public List<Statement> visitStringPartIfThenElse(org.rascalmpl.ast.StringPart.IfThenElse x) {
			List<Statement> stats = new ArrayList<>();

			stats.addAll(x.getPreStats());
			stats.addAll(body(x.getBody()));
			stats.addAll(x.getPostStats());
			
			List<Statement> elseStats = new ArrayList<>();
			elseStats.addAll(x.getPreStatsElse());
			stats.addAll(body(x.getElseBody()));
			elseStats.addAll(x.getPostStatsElse());

			return single(ASTBuilder.makeStat("IfThenElse", x.getLocation(), ASTBuilder.make("Label","Empty",x.getLocation()), 
					x.getConditions(), makeBlock(x.getLocation(), stats), makeBlock(x.getLocation(), elseStats)));
		}
		
		@Override
		public List<Statement> visitStringPartWhile(org.rascalmpl.ast.StringPart.While x) {
			List<Statement> stats = new ArrayList<Statement>();
			
			stats.addAll(x.getPreStats());
			stats.addAll(body(x.getBody()));
			stats.addAll(x.getPostStats());
			
			return single(ASTBuilder.makeStat("While", x.getLocation(), ASTBuilder.make("Label","Empty", x.getLocation()), Collections.singletonList(x.getCondition()), 
					makeBlock(x.getLocation(), stats)));
		}
		
		@Override
		public List<Statement> visitStringPartCharacters(org.rascalmpl.ast.StringPart.Characters x) {
			// characters are only recognized after the current indent, so they do not contribute
			// to the indentation. This means that if there are characters here, they will not influence the indent.
			// literal has to be indented with as much as the current indent.
			String tmp = indentation;
			indentation = "";
			return single(new ConstAppend(x.getLocation(), makeTarget(x.getLocation()), ((IString) x.interpret(null).getValue()).getValue(), tmp));
		}
	}
}
