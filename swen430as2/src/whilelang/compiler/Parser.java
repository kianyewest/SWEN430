// This file is part of the WhileLang Compiler (wlc).
//
// The WhileLang Compiler is free software; you can redistribute
// it and/or modify it under the terms of the GNU General Public
// License as published by the Free Software Foundation; either
// version 3 of the License, or (at your option) any later version.
//
// The WhileLang Compiler is distributed in the hope that it
// will be useful, but WITHOUT ANY WARRANTY; without even the
// implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
// PURPOSE. See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public
// License along with the WhileLang Compiler. If not, see
// <http://www.gnu.org/licenses/>
//
// Copyright 2013, David James Pearce.

package whilelang.compiler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import whilelang.ast.Attribute;
import whilelang.ast.Expr;
import whilelang.ast.Stmt;
import whilelang.ast.Type;
import whilelang.ast.WhileFile;
import whilelang.ast.WhileFile.Decl;
import whilelang.ast.WhileFile.Parameter;
import whilelang.ast.WhileFile.TypeDecl;
import whilelang.compiler.Lexer.Bar;
import whilelang.compiler.Lexer.Dot;
import whilelang.compiler.Lexer.EndOfStream;
import whilelang.compiler.Lexer.Equals;
import whilelang.compiler.Lexer.EqualsEquals;
import whilelang.compiler.Lexer.GreaterEquals;
import whilelang.compiler.Lexer.Identifier;
import whilelang.compiler.Lexer.Int;
import whilelang.compiler.Lexer.Keyword;
import whilelang.compiler.Lexer.LeftAngle;
import whilelang.compiler.Lexer.LeftBrace;
import whilelang.compiler.Lexer.LeftCurly;
import whilelang.compiler.Lexer.LeftSquare;
import whilelang.compiler.Lexer.LessEquals;
import whilelang.compiler.Lexer.LogicalAnd;
import whilelang.compiler.Lexer.LogicalOr;
import whilelang.compiler.Lexer.Minus;
import whilelang.compiler.Lexer.NotEquals;
import whilelang.compiler.Lexer.Percent;
import whilelang.compiler.Lexer.Plus;
import whilelang.compiler.Lexer.RightAngle;
import whilelang.compiler.Lexer.RightBrace;
import whilelang.compiler.Lexer.RightCurly;
import whilelang.compiler.Lexer.RightSlash;
import whilelang.compiler.Lexer.RightSquare;
import whilelang.compiler.Lexer.SemiColon;
import whilelang.compiler.Lexer.Shreak;
import whilelang.compiler.Lexer.Star;
import whilelang.compiler.Lexer.Strung;
import whilelang.compiler.Lexer.Token;
import whilelang.util.Pair;
import whilelang.util.SyntaxError;

public class Parser {

	private String filename;
	private ArrayList<Token> tokens;
	private HashMap<String,WhileFile.MethodDecl> userDefinedMethods;
	private HashSet<String> userDefinedTypes;
	private int index;

	public Parser(String filename, List<Token> tokens) {
		this.filename = filename;
		this.tokens = new ArrayList<Token>(tokens);
		this.userDefinedMethods = new HashMap<String,WhileFile.MethodDecl>();
		this.userDefinedTypes = new HashSet<String>();
	}

	/**
	 * Parse a given source file to produce its Abstract Syntax Tree
	 * representation.
	 *
	 * @return
	 */
	public WhileFile read() {
		ArrayList<Decl> decls = new ArrayList<Decl>();

		while (!(tokens.get(index) instanceof EndOfStream)) {
			Token t = tokens.get(index);
			if (t instanceof Keyword) {
				if (t.text.equals("type")) {
					decls.add(parseTypeDeclaration());
				} else {
					decls.add(parseMethodDeclaration());
				}
			} else {
				decls.add(parseMethodDeclaration());
			}
		}

		return new WhileFile(filename, decls);
	}

	/**
	 * Parse a type declaration of the following form:
	 *
	 * <pre>
	 * TypeDecl ::= 'type' Ident 'is' Type
	 * </pre>
	 *
	 * @return
	 */
	private Decl parseTypeDeclaration() {
		int start = index;
		matchKeyword("type");

		Identifier name = matchIdentifier();
		if(userDefinedTypes.contains(name.text)) {
			syntaxError("PR03", "type already declared",name);
		}
		matchKeyword("is");

		Type t = parseType();
		int end = index;
		userDefinedTypes.add(name.text);
		return new TypeDecl(t, name.text, sourceAttr(start, end - 1));
	}

	/**
	 * Parse a method declaration, of the form:
	 *
	 * <pre>
	 * MethodDecl ::= Type Ident '(' Parameters ')' '{' Stmt* '}'
	 *
	 * Parameters ::= [Type Ident (',' Type Ident)* ]
	 * </pre>
	 *
	 * @return
	 */
	private WhileFile.MethodDecl parseMethodDeclaration() {
		int start = index;

		Type returnType = parseType();
		Identifier name = matchIdentifier();
		if(userDefinedMethods.containsKey(name.text)) {
			syntaxError("PR03", "method already declared",name);
		}

		Context context = new Context();
		match("(");

		// Now build up the parameter types
		List<Parameter> parameters = new ArrayList<Parameter>();
		boolean firstTime = true;
		while (!(tokens.get(index) instanceof RightBrace)) {
			if (!firstTime) {
				match(",");
			}
			firstTime = false;
			int parameterStart = index;
			Type parameterType = parseType();
			Identifier parameterName = matchIdentifier();
			if(context.isDeclared(parameterName.text)) {
				syntaxError("PR03", "parameter " + parameterName.text + " already declared",parameterName);
			} else {
				context.declare(parameterName.text);
			}
			parameters.add(new Parameter(parameterType, parameterName.text, sourceAttr(parameterStart, index - 1)));

		}

		match(")");
		List<Stmt> stmts = parseStatementBlock(context);
		WhileFile.MethodDecl m = new WhileFile.MethodDecl(name.text, returnType, parameters, stmts, sourceAttr(start, index - 1));
		userDefinedMethods.put(name.text,m);
		return m;
	}

	/**
	 * Parse a block of zero or more statements, of the form:
	 *
	 * <pre>
	 * StmtBlock ::= '{' Stmt* '}'
	 * </pre>
	 *
	 * @return
	 */
	private List<Stmt> parseStatementBlock(Context context) {
		match("{");

		ArrayList<Stmt> stmts = new ArrayList<Stmt>();
		while (!(tokens.get(index) instanceof RightCurly)) {
			stmts.add(parseStatement(context));
		}

		match("}");

		return stmts;
	}

	/**
	 * Parse a given statement.
	 *
	 * @return
	 */
	private Stmt parseStatement(Context context) {
		checkNotEof();
		Token token = tokens.get(index);
		if (token.text.equals("if")) {
			return parseIfStmt(context);
		} else if (token.text.equals("while")) {
			return parseWhileStmt(context);
		} else if (token.text.equals("for")) {
			return parseForStmt(context);
		} else if (token.text.equals("switch")) {
			return parseSwitchStmt(context);
		} else {
			Stmt stmt = parseUnitStatement(context);
			match(";");
			return stmt;
		}
	}

	/**
	 * Parse a unit statement.
	 *
	 * @param context
	 * @return
	 */
	private Stmt parseUnitStatement(Context context) {
		checkNotEof();
		Token token = tokens.get(index);
		Stmt stmt;
		if (token.text.equals("assert")) {
			return parseAssertStmt(context);
		} else if (token.text.equals("print")) {
			return parsePrintStmt(context);
		} else if (token.text.equals("return")) {
			return parseReturnStmt(context);
		} else if (token.text.equals("break")) {
			return parseBreakStmt(context);
		} else if (token.text.equals("continue")) {
			return parseContinueStmt(context);
		} else if ((index + 1) < tokens.size() && tokens.get(index + 1) instanceof LeftBrace) {
			// must be a method invocation
			return parseInvokeExprOrStmt(context);
		} else if (isTypeAhead(index)) {
			return parseVariableDeclaration(context);
		} else {
			// invocation or assignment
			int start = index;
			Expr t = parseExpr(context);
			if (t instanceof Expr.Invoke) {
				stmt = (Expr.Invoke) t;
			} else {
				index = start;
				stmt = parseAssignStmt(context);
			}
		}
		return stmt;
	}


	/**
	 * Check whether there is a type starting at the given index. This is useful
	 * for distinguishing variable declarations from invocations and
	 * assignments.
	 *
	 * @param index
	 * @return
	 */
	private boolean isTypeAhead(int index) {
		if (index >= tokens.size()) {
			return false;
		}
		Token lookahead = tokens.get(index);
		if (lookahead instanceof Keyword) {
			return lookahead.text.equals("null") || lookahead.text.equals("bool") || lookahead.text.equals("int")
					|| lookahead.text.equals("char") || lookahead.text.equals("string");
		} else if (lookahead instanceof Identifier) {
			Identifier id = (Identifier) lookahead;
			return userDefinedTypes.contains(id.text);
		} else if (lookahead instanceof LeftCurly) {
			return isTypeAhead(index + 1);
		} else if (lookahead instanceof LeftSquare) {
			return isTypeAhead(index + 1);
		}

		return false;
	}

	/**
	 * Parse an assert statement, of the form:
	 *
	 * <pre>
	 * AssertStmt ::= 'assert'  Expr ';'
	 * </pre>
	 *
	 * @return
	 */
	private Stmt.Assert parseAssertStmt(Context context) {
		int start = index;
		// Every assert statement begins with the assert keyword!
		matchKeyword("assert");
		Expr e = parseExpr(context);
		// Done.
		return new Stmt.Assert(e, sourceAttr(start, index - 1));
	}
	
	/**
	 * Parse a print statement, of the form:
	 *
	 * <pre>
	 * PrintStmt ::= 'print'  Expr ';'
	 * </pre>
	 *
	 * @return
	 */
	private Stmt.Print parsePrintStmt(Context context) {
		int start = index;
		// Every print statement begins with the print keyword!
		matchKeyword("print");
		Expr e = parseExpr(context);
		// Done.
		return new Stmt.Print(e, sourceAttr(start, index - 1));
	}

	/**
	 * Parse an assignment statement, of the form:
	 *
	 * <pre>
	 * AssignStmt ::= LVal '=' Expr ';'
	 *
	 * LVal ::= Ident
	 *       | LVal '.' Ident
	 *       | LVal '[' Expr ']'
	 * </pre>
	 *
	 * @return
	 */
	private Stmt parseAssignStmt(Context context) {
		// standard assignment
		int start = index;
		Expr lhs = parseExpr(context);
		if (!(lhs instanceof Expr.LVal)) {
			syntaxError("PR10", "expecting lval, found " + lhs + ".", lhs);
		}
		match("=");
		Expr rhs = parseExpr(context);
		int end = index;
		return new Stmt.Assign((Expr.LVal) lhs, rhs, sourceAttr(start, end - 1));
	}

	/**
	 * Parse a variable declaration, of the form:
	 *
	 * <pre>
	 * VarDecl ::= Type Ident [ '=' Expr ] ';'
	 * </pre>
	 *
	 * @return
	 */
	private Stmt.VariableDeclaration parseVariableDeclaration(Context context) {
		int start = index;
		// Every variable declaration consists of a declared type and variable
		// name.
		Type type = parseType();
		Identifier id = matchIdentifier();
		if(context.isDeclared(id.text)) {
			syntaxError("PR03", "variable " + id.text + " alread declared",id);
		} else {
			context.declare(id.text);
		}
		// A variable declaration may optionally be assigned an initialiser
		// expression.
		Expr initialiser = null;
		if (tokens.get(index) instanceof Equals) {
			match("=");
			initialiser = parseExpr(context);
		}
		// Done.
		return new Stmt.VariableDeclaration(type, id.text, initialiser, sourceAttr(start, index - 1));
	}

	/**
	 * Parse an if statement, of the form:
	 *
	 * <pre>
	 * IfStmt ::= 'if' '(' Expr ')' StmtBlock ElseIf* [Else]
	 *
	 * ElseIf ::= 'else' 'if' '(' Expr ')' StmtBlock
	 *
	 * Else ::= 'else' StmtBlock
	 * </pre>
	 *
	 * @return
	 */
	private Stmt parseIfStmt(Context context) {
		int start = index;
		matchKeyword("if");
		match("(");
		Expr c = parseExpr(context);
		match(")");
		int end = index;
		List<Stmt> tblk = parseStatementBlock(context.clone());
		List<Stmt> fblk = Collections.emptyList();

		if (tokens.get(index).text.equals("else")) {
			matchKeyword("else");

			if (tokens.get(index).text.equals("if")) {
				Stmt if2 = parseIfStmt(context);
				fblk = new ArrayList<Stmt>();
				fblk.add(if2);
			} else {
				fblk = parseStatementBlock(context.clone());
			}
		}

		return new Stmt.IfElse(c, tblk, fblk, sourceAttr(start, end - 1));
	}

	/**
	 * Parse a return statement, of the form:
	 *
	 * <pre>
	 * ReturnStmt ::= 'return' [ Expr ] ';'
	 * </pre>
	 *
	 * @return
	 */
	private Stmt.Return parseReturnStmt(Context context) {
		int start = index;
		// Every return statement begins with the return keyword!
		matchKeyword("return");
		Expr e = null;
		// A return statement may optionally have a return expression.
		if (!(tokens.get(index) instanceof SemiColon)) {
			e = parseExpr(context);
		}
		// Done.
		return new Stmt.Return(e, sourceAttr(start, index - 1));
	}

	/**
	 * Parse a While statement of the form:
	 *
	 * <pre>
	 * WhileStmt ::= 'while' '(' Expr ')' StmtBlock
	 * </pre>
	 *
	 * @return
	 */
	private Stmt parseWhileStmt(Context context) {
		int start = index;
		matchKeyword("while");
		match("(");
		Expr condition = parseExpr(context);
		match(")");
		int end = index;
		List<Stmt> blk = parseStatementBlock(context.setInLoop().clone());
		return new Stmt.While(condition, blk, sourceAttr(start, end - 1));
	}

	/**
	 * Parse a for statement, of the form:
	 *
	 * <pre>
	 * ForStmt ::=
	 * </pre>
	 *
	 * @return
	 */
	private Stmt parseForStmt(Context context) {
		int start = index;
		matchKeyword("for");
		match("(");
		Stmt.VariableDeclaration declaration = parseVariableDeclaration(context);
		match(";");
		Expr condition = parseExpr(context);
		match(";");
		Stmt increment = parseUnitStatement(context);
		int end = index;
		match(")");
		List<Stmt> blk = parseStatementBlock(context.setInLoop().clone());

		return new Stmt.For(declaration, condition, increment, blk, sourceAttr(start, end - 1));
	}

	/**
	 * Parse a Switch statement of the form:
	 *
	 * <pre>
	 * SwitchStmt ::= 'switch' '(' Expr ')' '{' CaseBlock+ [DefaultBlock] '}'
	 *
	 * CaseBlock ::= 'case' Expr ':' Stmt*
	 *
	 * DefaultBlock ::= 'default' ':' Stmt*
	 * </pre>
	 *
	 * @return
	 */
	private Stmt parseSwitchStmt(Context context) {
		int start = index;
		matchKeyword("switch");
		match("(");
		Expr expr = parseExpr(context);
		match(")");
		int end = index;
		match("{");
		List<Stmt.Case> cases = parseSwitchCases(context.setInSwitch());
		match("}");

		return new Stmt.Switch(expr, cases, sourceAttr(start, end - 1));
	}

	private Stmt parseIsStmt(Context context) {
		int start = index;
		matchKeyword("switch");
		match("(");
		Expr expr = parseExpr(context);
		match(")");
		int end = index;
		match("{");
		List<Stmt.Case> cases = parseSwitchCases(context.setInSwitch());
		match("}");

		return new Stmt.Switch(expr, cases, sourceAttr(start, end - 1));
	}

	private Stmt parseBreakStmt(Context context) {
		int start = index;
		Keyword k = matchKeyword("break");
		if(!context.inLoop() && !context.inSwitch()) {
			syntaxError("PR09", "break outside switch or loop",k);
		}
		return new Stmt.Break(sourceAttr(start, index - 1));
	}

	private Stmt parseContinueStmt(Context context) {
		int start = index;
		Keyword k = matchKeyword("continue");
		if(!context.inLoop()) {
			syntaxError("PR09", "continue outside of loop",k);
		}
		return new Stmt.Continue(sourceAttr(start, index - 1));
	}
	/**
	 * Parse the list of zero or more case blocks which make up a switch
	 * statement.
	 *
	 * @return
	 */
	private List<Stmt.Case> parseSwitchCases(Context context) {
		ArrayList<Stmt.Case> cases = new ArrayList<Stmt.Case>();
		HashSet<Object> values = new HashSet<Object>();

		while(!(tokens.get(index) instanceof RightCurly)) {
			int start = index;
			Expr.Literal value;
			Token lookahead = tokens.get(index);
			if(lookahead.text.equals("case")) {
				// This is a case block
				matchKeyword("case");
				value = parseConstant();
				if(values.contains(value.getValue())) {
					syntaxError("PR07", "duplicate case",value);
				} else {
					values.add(value.getValue());
				}
				match(":");
			} else {
				// This must be a default block
				matchKeyword("default");
				match(":");
				value = null;
			}
			int end = index;
			// Parse the case body
			ArrayList<Stmt> body = new ArrayList<Stmt>();
			while (!(tokens.get(index) instanceof RightCurly)
					&& !(tokens.get(index).text.equals("case")) && !(tokens.get(index).text.equals("default"))) {
				body.add(parseStatement(context));
			}
			cases.add(new Stmt.Case(value, body, sourceAttr(start,end-1)));
		}
		return cases;
	}

	private Expr.Literal parseConstant() {
		Expr e = parseExpr(new Context());
		Object constant = parseConstant(e);
		return new Expr.Literal(constant,e.attributes());
	}

	private Object parseConstant(Expr e) {
		if(e instanceof Expr.Literal) {
			return ((Expr.Literal) e).getValue();
		} else if(e instanceof Expr.ArrayInitialiser) {
			Expr.ArrayInitialiser ai = (Expr.ArrayInitialiser) e;
			ArrayList<Object> vals = new ArrayList<Object>();
			for(Expr element : ai.getArguments()) {
				vals.add(parseConstant(element));
			}
			return vals;
		} else if(e instanceof Expr.RecordConstructor) {
			Expr.RecordConstructor rc = (Expr.RecordConstructor) e;
			HashMap<String,Object> vals = new HashMap<String,Object>();
			for(Pair<String,Expr> p : rc.getFields()) {
				vals.put(p.first(), parseConstant(p.second()));
			}
			return vals;
		} else {
			// Problem
			syntaxError("PR08", "constant expression expected", e);
			return null;
		}
	}

	private Expr parseExpr(Context context) {
		checkNotEof();
		int start = index;

		Expr c1 = parseRelationalExpr(context);
		Token t =tokens.get(index);
		if (t instanceof Keyword && "is".equals(((Keyword)t).text)){
			match("is");
			Type c2 = parseType();
			return new Expr.Is(c2,c1, sourceAttr(start, index - 1));
		}else if (t instanceof LogicalAnd) {
			match("&&");
			Expr c2 = parseExpr(context);
			return new Expr.Binary(Expr.BOp.AND, c1, c2, sourceAttr(start, index - 1));
		} else if (t instanceof LogicalOr) {
			match("||");
			Expr c2 = parseExpr(context);
			return new Expr.Binary(Expr.BOp.OR, c1, c2, sourceAttr(start, index - 1));
		}

		return c1;
	}

	private Expr parseRelationalExpr(Context context) {
		int start = index;

		Expr lhs = parseAdditiveExpr(context);

		if (tokens.get(index) instanceof LessEquals) {
			match("<=");
			Expr rhs = parseAdditiveExpr(context);
			return new Expr.Binary(Expr.BOp.LTEQ, lhs, rhs, sourceAttr(start, index - 1));
		} else if (tokens.get(index) instanceof LeftAngle) {
			match("<");
			Expr rhs = parseAdditiveExpr(context);
			return new Expr.Binary(Expr.BOp.LT, lhs, rhs, sourceAttr(start, index - 1));
		} else if (tokens.get(index) instanceof GreaterEquals) {
			match(">=");
			Expr rhs = parseAdditiveExpr(context);
			return new Expr.Binary(Expr.BOp.GTEQ, lhs, rhs, sourceAttr(start, index - 1));
		} else if (tokens.get(index) instanceof RightAngle) {
			match(">");
			Expr rhs = parseAdditiveExpr(context);
			return new Expr.Binary(Expr.BOp.GT, lhs, rhs, sourceAttr(start, index - 1));
		} else if (tokens.get(index) instanceof EqualsEquals) {
			match("==");
			Expr rhs = parseAdditiveExpr(context);
			return new Expr.Binary(Expr.BOp.EQ, lhs, rhs, sourceAttr(start, index - 1));
		} else if (tokens.get(index) instanceof NotEquals) {
			match("!=");
			Expr rhs = parseAdditiveExpr(context);
			return new Expr.Binary(Expr.BOp.NEQ, lhs, rhs, sourceAttr(start, index - 1));
		} else {
			return lhs;
		}
	}

	private Expr parseAdditiveExpr(Context context) {
		int start = index;
		Expr lhs = parseMultplicativeExpr(context);

		while (isAdditiveOperator(tokens.get(index))) {
			Expr.BOp kind = getBinaryOperator(tokens.get(index));
			// Match operator
			match("+","-");
			// Parse right-hand side
			Expr rhs = parseMultplicativeExpr(context);
			// Construct additive node
			lhs = new Expr.Binary(kind, lhs, rhs, sourceAttr(start, index - 1));
		}

		return lhs;
	}

	private boolean isAdditiveOperator(Token token) {
		return token instanceof Plus || token instanceof Minus;
	}

	private Expr parseMultplicativeExpr(Context context) {
		int start = index;
		Expr lhs = parseIndexTerm(context);

		while (isMultiplicativeOperator(tokens.get(index))) {
			Expr.BOp kind = getBinaryOperator(tokens.get(index));
			// match operator
			match("*","/","%");
			// Parse right-hand side
			Expr rhs = parseIndexTerm(context);
			// Construct multiplicative node
			lhs = new Expr.Binary(kind, lhs, rhs, sourceAttr(start, index - 1));
		}

		return lhs;
	}

	private boolean isMultiplicativeOperator(Token token) {
		return token instanceof Star || token instanceof RightSlash || token instanceof Percent;
	}

	private Expr parseIndexTerm(Context context) {
		checkNotEof();
		int start = index;
		Expr lhs = parseTerm(context);

		Token lookahead = tokens.get(index);

		while (lookahead instanceof LeftSquare || lookahead instanceof Dot || lookahead instanceof LeftBrace) {
			if (lookahead instanceof LeftSquare) {
				match("[");
				Expr rhs = parseAdditiveExpr(context);
				match("]");
				lhs = new Expr.IndexOf(lhs, rhs, sourceAttr(start, index - 1));
			} else {
				match(".");
				String name = matchIdentifier().text;
				lhs = new Expr.RecordAccess(lhs, name, sourceAttr(start, index - 1));
			}
			lookahead = tokens.get(index);
		}

		return lhs;
	}

	private Expr parseCast(Context context){
		Token token = tokens.get(index);

		if(token instanceof LeftBrace) {
			match("(");
			Type t;
			try {
				t = parseType();
			}catch (SyntaxError e){
				return null;
			}
			match(")");
			Expr expr = parseExpr(context);

			Expr e = new Expr.Cast(t, expr);
			return e;

		}
		return null;
	}

	private Expr parseTerm(Context context) {
		checkNotEof();

		int start = index;
		Token token = tokens.get(index);

		if(token instanceof LeftBrace) {
			int startIndex = index;
			Expr e = parseCast(context);

			if(e==null ) {
				index = startIndex;
				match("(");
				e = parseExpr(context);
				match(")");
			}
			return e;
		}

//
//		Expr e = parseCast(context);
//
//		if(e!=null){
//			return e;
//		}
		if ((index + 1) < tokens.size() && token instanceof Identifier
				&& tokens.get(index + 1) instanceof LeftBrace) {
			// must be a method invocation
			return parseInvokeExprOrStmt(context);
		} else if (token.text.equals("null")) {
			matchKeyword("null");
			return new Expr.Literal(null, sourceAttr(start, index - 1));
		} else if (token.text.equals("true")) {
			matchKeyword("true");
			return new Expr.Literal(true, sourceAttr(start, index - 1));
		} else if (token.text.equals("false")) {
			matchKeyword("false");
			return new Expr.Literal(false, sourceAttr(start, index - 1));
		} else if (token instanceof Identifier) {
			return parseVariable(context);
		} else if (token instanceof Lexer.Char) {
			char val = match(Lexer.Char.class, "a character").value;
			return new Expr.Literal(Character.valueOf(val), sourceAttr(start, index - 1)); 
		} else if (token instanceof Int) {
			int val = match(Int.class, "an integer").value;
			return new Expr.Literal(val, sourceAttr(start, index - 1));
		} else if (token instanceof Strung) {
			return parseString();
		} else if (token instanceof Minus) {
			return parseNegationExpr(context);
		} else if (token instanceof Bar) {
			return parseArrayLengthExpr(context);
		} else if (token instanceof LeftSquare) {
			return parseArrayInitialiserOrGeneratorExpr(context);
		} else if (token instanceof LeftCurly) {
			return parseRecordInitialiserExpr(context);
		} else if (token instanceof Shreak) {
			match("!");
			return new Expr.Unary(Expr.UOp.NOT, parseTerm(context), sourceAttr(start, index - 1));
		}
		syntaxError("PR11", "unrecognised term (\"" + token.text + "\")", token);
		return null;
	}

	private Expr parseVariable(Context context) {
		int start = index;
		Identifier var = matchIdentifier();
		if(context.isDeclared(var.text)) {
			return new Expr.Variable(var.text, sourceAttr(start, index - 1));
		} else {
			syntaxError("PR04", "unknown variable " + var.text, var);
			return null;
		}
	}

	private Expr parseArrayInitialiserOrGeneratorExpr(Context context) {
		int start = index;
		ArrayList<Expr> exprs = new ArrayList<Expr>();
		match("[");
		checkNotEof();
		Token token = tokens.get(index);
		// Check for array generator expression
		if (!(token instanceof RightSquare)) {
			exprs.add(parseExpr(context));
			checkNotEof();
			token = tokens.get(index);
			if (token instanceof SemiColon) {
				// Array generator
				match(";");
				exprs.add(parseExpr(context));
				checkNotEof();
				match("]");
				return new Expr.ArrayGenerator(exprs.get(0), exprs.get(1), sourceAttr(start, index - 1));
			} else {
				// Array initialiser
				while (!(token instanceof RightSquare)) {
					match(",");
					exprs.add(parseExpr(context));
					checkNotEof();
					token = tokens.get(index);
				}
			}
		}

		match("]");
		return new Expr.ArrayInitialiser(exprs, sourceAttr(start, index - 1));
	}

	private Expr parseRecordInitialiserExpr(Context context) {
		int start = index;
		match("{");
		HashSet<String> keys = new HashSet<String>();
		ArrayList<Pair<String, Expr>> exprs = new ArrayList<Pair<String, Expr>>();
		checkNotEof();
		Token token = tokens.get(index);
		boolean firstTime = true;
		while (!(token instanceof RightCurly)) {
			if (!firstTime) {
				match(",");
			}
			firstTime = false;

			checkNotEof();
			token = tokens.get(index);
			Identifier n = matchIdentifier();

			if (keys.contains(n.text)) {
				syntaxError("PR05", "duplicate tuple key", n);
			}

			match(":");

			Expr e = parseExpr(context);
			exprs.add(new Pair<String, Expr>(n.text, e));
			keys.add(n.text);
			checkNotEof();
			token = tokens.get(index);
		}
		match("}");
		return new Expr.RecordConstructor(exprs, sourceAttr(start, index - 1));
	}

	private Expr parseArrayLengthExpr(Context context) {
		int start = index;
		match("|");
		Expr e = parseIndexTerm(context);
		match("|");
		return new Expr.Unary(Expr.UOp.LENGTHOF, e, sourceAttr(start, index - 1));
	}

	private Expr parseNegationExpr(Context context) {
		int start = index;
		match("-");
		Expr e = parseIndexTerm(context);

		if (e instanceof Expr.Literal) {
			Expr.Literal c = (Expr.Literal) e;
			if (c.getValue() instanceof Integer) {
				int bi = (Integer) c.getValue();
				return new Expr.Literal(-bi, sourceAttr(start, index));
			}
		}

		return new Expr.Unary(Expr.UOp.NEG, e, sourceAttr(start, index));
	}

	private Expr.Invoke parseInvokeExprOrStmt(Context context) {
		int start = index;
		Identifier name = matchIdentifier();
		if(!userDefinedMethods.containsKey(name.text)) {
			syntaxError("PR04", "unknown method " + name.text + "()",name);
		}
		match("(");
		boolean firstTime = true;
		ArrayList<Expr> args = new ArrayList<Expr>();
		while (!(tokens.get(index) instanceof RightBrace)) {
			if (!firstTime) {
				match(",");
			} else {
				firstTime = false;
			}
			Expr e = parseExpr(context);

			args.add(e);
		}
		match(")");
		WhileFile.MethodDecl m = userDefinedMethods.get(name.text);
		Expr.Invoke invoke = new Expr.Invoke(name.text, args, sourceAttr(start, index - 1));

		if(m.getParameters().size() != args.size()) {
			syntaxError("PR06", "incorrect number of arguments provided",invoke);
		}

		return invoke;
	}

	private Expr parseString() {
		int start = index;
		String s = match(Strung.class, "a string").string;
		return new Expr.Literal(s, sourceAttr(start, index - 1));
	}

	private Type parseType() {
		int start = index;
		checkNotEof();
		// Determine base type
		Type type = parseBaseType();

		// Determine array level (if any)
		while (tokens.get(index) instanceof LeftSquare) {
			match("[");
			match("]");
			type = new Type.Array(type, sourceAttr(start, index - 1));
		}

		Token token = tokens.get(index);
		if(token instanceof Bar){
			index++;
			Type t = parseType();
			Set<Type> list_type = new HashSet<>();
			list_type.add(type);
			list_type.add(t);
			return new Type.Union(list_type);
		}
		// Done
		return type;
	}

	/**
	 * Parse a "base" type. That is any type which could be the element of an
	 * array type.
	 *
	 * @return
	 */
	private Type parseBaseType() {
		int start = index;
		Token token = tokens.get(index);
		if (token.text.equals("int")) {
			matchKeyword("int");
			return new Type.Int(sourceAttr(start, index - 1));
		} else if (token.text.equals("void")) {
			matchKeyword("void");
			return new Type.Void(sourceAttr(start, index - 1));
		} else if (token.text.equals("null")) {
			matchKeyword("null");
			return new Type.Null(sourceAttr(start, index - 1));
		} else if (token.text.equals("bool")) {
			matchKeyword("bool");
			return new Type.Bool(sourceAttr(start, index - 1));
		} else if (token instanceof LeftCurly) {
			// record type
			return parseRecordType();
		} else {
			Identifier id = matchIdentifier();
			if(userDefinedTypes.contains(id.text)) {
				return new Type.Named(id.text, sourceAttr(start, index - 1));
			} else {
				syntaxError("PR04", "unknown type " + id.text,id);
				return null;
			}
		}
	}

	/**
	 * Parse a record type, which takes the form:
	 *
	 * <pre>
	 * RecordType ::= '{' Type Indent ( ',' Type Indent )* '}'
	 * </pre>
	 *
	 * This function additionally checks that no two fields have the same name.
	 *
	 * @return
	 */
	private Type.Record parseRecordType() {
		int start = index;
		match("{");
		// The fields set tracks the field names we've already seen
		HashSet<String> fields = new HashSet<String>();
		ArrayList<Pair<Type,String>> types = new ArrayList<Pair<Type,String>>();

		Token token = tokens.get(index);
		boolean firstTime = true;
		while (!(token instanceof RightCurly)) {
			if (!firstTime) {
				match(",");
			}
			firstTime = false;
			checkNotEof();

			token = tokens.get(index);
			Type type = parseType();
			Identifier n = matchIdentifier();

			if (fields.contains(n.text)) {
				syntaxError("PR05", "duplicate field", n);
			}
			types.add(new Pair<Type,String>(type,n.text));
			fields.add(n.text);
			checkNotEof();
			token = tokens.get(index);
		}
		match("}");
		return new Type.Record(types, sourceAttr(start, index - 1));
	}

	/**
	 * Convert a given token into a binary operator kind.
	 *
	 * @param token
	 * @return
	 */
	private Expr.BOp getBinaryOperator(Token token) {
		if(token instanceof Plus) {
			return Expr.BOp.ADD;
		} else if(token instanceof Minus) {
			return Expr.BOp.SUB;
		} else if(token instanceof Star) {
			return Expr.BOp.MUL;
		} else if(token instanceof RightSlash) {
			return Expr.BOp.DIV;
		} else if(token instanceof Percent) {
			return Expr.BOp.REM;
		} else {
			throw new IllegalArgumentException("invalid binary operator '" + token + "'");
		}
	}

	private void checkNotEof() {
		if (index >= tokens.size()) {
			throw new SyntaxError("PR02", "unexpected end-of-file", filename, index - 1, index - 1);
		}
		return;
	}

	private Token match(String op) {
		checkNotEof();
		Token t = tokens.get(index);
		if (!t.text.equals(op)) {
			syntaxError("PR01", "expecting '" + op + "', found '" + t.text + "'", t);
		}
		index = index + 1;
		return t;
	}

	private Token match(String... ops) {
		checkNotEof();
		Token t = tokens.get(index);
		for (String op : ops) {
			if (t.text.equals(op)) {
				index = index + 1;
				return t;
			}
		}
		syntaxError("PR01", "expecting one of " + Arrays.toString(ops) + ", found '" + t.text + "'", t);
		return null; // deadcode
	}

	@SuppressWarnings("unchecked")
	private <T extends Token> T match(Class<T> c, String name) {
		checkNotEof();
		Token t = tokens.get(index);
		if (!c.isInstance(t)) {
			syntaxError("PR01", "expecting " + name + ", found '" + t.text + "'", t);
		}
		index = index + 1;
		return (T) t;
	}

	private Identifier matchIdentifier() {
		checkNotEof();
		Token t = tokens.get(index);
		if (t instanceof Identifier) {
			Identifier i = (Identifier) t;
			index = index + 1;
			return i;
		}
		syntaxError("PR01", "identifier expected", t);
		return null; // unreachable.
	}

	private Keyword matchKeyword(String keyword) {
		checkNotEof();
		Token t = tokens.get(index);
		if (t instanceof Keyword) {
			if (t.text.equals(keyword)) {
				index = index + 1;
				return (Keyword) t;
			}
		}
		syntaxError("PR01", "keyword " + keyword + " expected.", t);
		return null;
	}

	private Attribute.Source sourceAttr(int start, int end) {
		Token t1 = tokens.get(start);
		Token t2 = tokens.get(end);
		return new Attribute.Source(t1.start, t2.end());
	}

	private void syntaxError(String code, String msg, Expr e) {
		Attribute.Source loc = e.attribute(Attribute.Source.class);
		throw new SyntaxError(code, msg, filename, loc.start, loc.end);
	}

	private void syntaxError(String code, String msg, Token t) {
		throw new SyntaxError(code, msg, filename, t.start, t.start + t.text.length() - 1);
	}

	/**
	 * Provides information about the current context in which the parser is
	 * operating.
	 *
	 * @author David J. Pearce
	 *
	 */
	private static class Context {
		/**
		 * indicates whether the current context is within a loop or not.
		 */
		private final boolean inLoop;

		/**
		 * indicates whether the current context is within a switch or not.
		 */
		private final boolean inSwitch;

		/**
		 * indicates the set of declared variables within the current context;
		 */
		private final Set<String> environment;

		public Context() {
			this.inLoop = false;
			this.inSwitch = false;
			this.environment = new HashSet<String>();
		}

		private Context(boolean inLoop, boolean inSwitch, Set<String> environment) {
			this.inLoop = inLoop;
			this.inSwitch = inSwitch;
			this.environment = environment;
		}

		/**
		 * Check whether the given context is within an enclosing loop or not.
		 *
		 * @return
		 */
		public boolean inLoop() {
			return inLoop;
		}

		/**
		 * Check whether the given context is within an enclosing switch or not.
		 *
		 * @return
		 */
		public boolean inSwitch() {
			return inSwitch;
		}

		/**
		 * Check whether a given variable is declared in this context or not.
		 *
		 * @param variable
		 * @return
		 */
		public boolean isDeclared(String variable) {
			return environment.contains(variable);
		}

		public void declare(String variable) {
			environment.add(variable);
		}

		/**
		 * Create a copy of this context which is enclosed within a loop
		 * statement.
		 *
		 * @return
		 */
		public Context setInLoop() {
			return new Context(true,inSwitch,environment);
		}

		/**
		 * Create a copy of this context which is enclosed within a switch
		 * statement.
		 *
		 * @return
		 */
		public Context setInSwitch() {
			return new Context(inLoop,true,environment);
		}

		/**
		 * Create a new clone of this context
		 */
		@Override
		public Context clone() {
			return new Context(inLoop,inSwitch,new HashSet<String>(environment));
		}
	}
}
