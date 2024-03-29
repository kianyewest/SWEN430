package whilelang.compiler;

import java.io.*;
import java.util.*;

import jasm.attributes.SourceFile;
import jasm.lang.Bytecode;
import jasm.lang.Bytecode.IfMode;
import jasm.lang.ClassFile;
import jasm.lang.JvmType;
import jasm.lang.JvmTypes;
import jasm.lang.Modifier;

import whilelang.ast.*;
import whilelang.util.Pair;

import static jasm.lang.Bytecode.InvokeMode.STATIC;
import static jasm.lang.Bytecode.InvokeMode.VIRTUAL;

/**
 * Responsible for translating a While source file into a JVM Class file.
 *
 * @author David J. Pearce
 *
 */
public class ClassFileWriter {
	// Look in the Java Virtual Machine spec for information about this this
	// number
	private static int CLASS_VERSION = 49;

	/**
	 * The Jasm classfile writer to which we will write our compiled While file.
	 * This takes care of lots of the messy bits of working with the JVM.
	 */
	private jasm.io.ClassFileWriter writer;

	/**
	 * Maps each declared type to its body
	 */
	private HashMap<String,Type> declaredTypes;

	/**
	 * Maps each declared method to its JvmType
	 */
	private HashMap<String,JvmType.Function> methodTypes;

	/**
	 * Construct a ClassFileWriter which will compile a given WhileFile into a
	 * JVM class file of the given name.
	 *
	 * @param classFile
	 * @throws FileNotFoundException
	 */
	public ClassFileWriter(String classFile) throws FileNotFoundException {
		writer = new jasm.io.ClassFileWriter(new FileOutputStream(classFile));
		declaredTypes = new HashMap<String,Type>();
		methodTypes = new HashMap<String,JvmType.Function>();
	}

	public void write(WhileFile sourceFile) throws IOException {
		String moduleName = new File(sourceFile.filename).getName().replace(".while","");
		// Modifiers for class
		List<Modifier> modifiers = Arrays.asList(Modifier.ACC_PUBLIC, Modifier.ACC_FINAL);
		// List of interfaces implemented by class
		List<JvmType.Clazz> implemented = new ArrayList<JvmType.Clazz>();
		// Base class for this class
		JvmType.Clazz superClass = JvmTypes.JAVA_LANG_OBJECT;
		// The class name for this class
		JvmType.Clazz owner = new JvmType.Clazz(moduleName);
		// Create the class!
		ClassFile cf = new ClassFile(CLASS_VERSION, owner, superClass, implemented, modifiers);
		// Add an attribute to the generated class file which indicates the
		// source file from which it was generated. This is useful for getting
		// better error messages out of the JVM.
		cf.attributes().add(new SourceFile(sourceFile.filename));
		// Now, we need to write out all methods defined in the WhileFile. We
		// don't need to worry about other forms of declaration though, as they
		// have no meaning on the JVM.
		for(WhileFile.Decl d : sourceFile.declarations) {
			if(d instanceof WhileFile.MethodDecl) {
				ClassFile.Method m = translate((WhileFile.MethodDecl) d, owner);
				cf.methods().add(m);
			} else if(d instanceof WhileFile.TypeDecl) {
				// Add the type to the map of declared types
				WhileFile.TypeDecl td = (WhileFile.TypeDecl) d;
				declaredTypes.put(td.getName(), td.getType());
			}
		}
		// Finally, write the generated classfile to disk
		writer.write(cf);
	}

	/**
	 * Translate a given WhileFile method into a ClassFile method.
	 *
	 * @param decl
	 */
	private ClassFile.Method translate(WhileFile.MethodDecl method, JvmType.Clazz owner) {
		// Modifiers for method
		List<Modifier> modifiers = Arrays.asList(Modifier.ACC_PUBLIC, Modifier.ACC_STATIC);
		// Construct type for method
		JvmType.Function ft = constructMethodType(method);
		// Construct method object
		ClassFile.Method cm = new ClassFile.Method(method.name(), ft, modifiers);
		// Generate bytecodes representing method body
		Context context = new Context(owner,constructMethodEnvironment(method));
		ArrayList<Bytecode> bytecodes = new ArrayList<Bytecode>();
		translate(method.getBody(),context,bytecodes);
		// Handle methods with missing return statements, as these need a
		// bytecode
		addReturnAsNecessary(method,bytecodes);
		//
		jasm.attributes.Code code = new jasm.attributes.Code(bytecodes, Collections.EMPTY_LIST, cm);
		// Finally, add the jvm Code attribute to this method
		cm.attributes().add(code);
		// Done
		String final_s = bytecodes.toString();

//		System.out.println("final: \n"+final_s.replace(",","\n"));
//		System.out.println("final: \n"+final_s);
		return cm;
	}

	/**
	 * Translate a list of statements in the While language into a series of
	 * bytecodes which implement their behaviour. The result indicates whether
	 * or not execution will fall-through to the next statement after this.
	 *
	 * @param stmts
	 *            The list of statements being translated
	 * @param environment
	 *            The current translation context
	 * @param bytecodes
	 *            The list of bytecodes being accumulated
	 */
	private void translate(List<Stmt> stmts, Context context, List<Bytecode> bytecodes) {
		for(Stmt s : stmts) {
			translate(s,context,bytecodes);
		}
	}

	/**
	 * Translate a given statement in the While language into a series of one of
	 * more bytecodes which implement its behaviour.
	 *
	 * @param stmt
	 *            The statement being translated
	 * @param environment
	 *            The current translation context
	 * @param bytecodes
	 *            The list of bytecodes being accumulated
	 */
	private void translate(Stmt stmt, Context context, List<Bytecode> bytecodes) {
		if(stmt instanceof Stmt.Assert) {
			translate((Stmt.Assert) stmt, context, bytecodes);
		} else if(stmt instanceof Stmt.Assign) {
			translate((Stmt.Assign) stmt, context, bytecodes);
		} else if(stmt instanceof Stmt.Break) {
			translate((Stmt.Break) stmt, context, bytecodes);
		} else if(stmt instanceof Stmt.Continue) {
			translate((Stmt.Continue) stmt, context, bytecodes);
		} else if(stmt instanceof Stmt.For) {
			translate((Stmt.For) stmt, context, bytecodes);
		} else if(stmt instanceof Stmt.IfElse) {
			translate((Stmt.IfElse) stmt, context, bytecodes);
		} else if(stmt instanceof Expr.Invoke) {
			translate((Expr.Invoke) stmt, context, bytecodes);
		} else if(stmt instanceof Stmt.While) {
			translate((Stmt.While) stmt, context, bytecodes);
		} else if(stmt instanceof Stmt.Return) {
			translate((Stmt.Return) stmt, context, bytecodes);
		} else if(stmt instanceof Stmt.Switch) {
			translate((Stmt.Switch) stmt, context, bytecodes);
		} else if(stmt instanceof Stmt.VariableDeclaration) {
			translate((Stmt.VariableDeclaration) stmt, context, bytecodes);
		} else {
			throw new IllegalArgumentException("Unknown statement encountered: " + stmt);
		}
	}

	private void translate(Stmt.Assert stmt, Context context, List<Bytecode> bytecodes) {
		String label = freshLabel();
		translate(stmt.getExpr(), context, bytecodes);
		bytecodes.add(new Bytecode.If(IfMode.NE, label));
		// If the assertion fails, through runtime exception
		constructObject(JvmTypes.JAVA_LANG_RUNTIMEEXCEPTION, bytecodes);
		bytecodes.add(new Bytecode.Throw());
		bytecodes.add(new Bytecode.Label(label));
	}

	private void throwException(List<Bytecode> bytecodes){
		constructObject(JvmTypes.JAVA_LANG_RUNTIMEEXCEPTION, bytecodes);
		bytecodes.add(new Bytecode.Throw());
	}

	private void print_byte_info(JvmType t, Context context,List<Bytecode> bytecodes) {

			//save existing value we are going to print

			String s = freshLabel();
			int reg = context.declareRegister(s);
			bytecodes.add(new Bytecode.Store(reg,t));

			JvmType.Clazz SYSTEM = new JvmType.Clazz("java.lang.System");

			JvmType.Clazz PRINTSTREAM = new JvmType.Clazz("java.io", "PrintStream");

			bytecodes.add(new Bytecode.GetField(SYSTEM, "out", PRINTSTREAM, Bytecode.FieldMode.STATIC));

//			bytecodes.add(new Bytecode.Load(reg,t));
			bytecodes.add(new Bytecode.LoadConst(99));
			JvmType.Function boxMethodType = new JvmType.Function(new JvmType.Void(), new JvmType.Int());
			bytecodes.add(new Bytecode.Invoke(PRINTSTREAM, "println", boxMethodType, VIRTUAL));

			//replace what we took to top of stack
			bytecodes.add(new Bytecode.Load(reg,t));

	}

	private void translate(Stmt.Assign stmt, Context context, List<Bytecode> bytecodes) {
		// translate assignment
		translateAssignmentHelper(stmt.getLhs(),stmt.getRhs(),context,bytecodes);
	}

	private void translate(Stmt.Break stmt, Context context, List<Bytecode> bytecodes) {
		String label = context.viewMostRecentLoop().get(EXIT_TEXT);
		bytecodes.add(new Bytecode.Goto(label));
	}

	private void translate(Stmt.Continue stmt, Context context, List<Bytecode> bytecodes) {
		String label = context.viewMostRecentLoop().get(END_ITERATION_TEXT);
		bytecodes.add(new Bytecode.Goto(label));

	}

	private final String CONDITIONAL_TEXT = "conditional";
	private final String END_ITERATION_TEXT = "incremental";
	private final String EXIT_TEXT = "exit";
	private int c = 0;

	private void translate(Stmt.For stmt, Context context, List<Bytecode> bytecodes) {
		//set up for
		translate(stmt.getDeclaration(),context,bytecodes);

		String trueLabel, exitLabel,conditionLabel,endIterationLabel;
		conditionLabel = freshLabel()+"_conditional";
		endIterationLabel = freshLabel()+"_increment";
		trueLabel = freshLabel()+"_true";
		exitLabel = freshLabel()+"_exit_for_loop_"+c;
		c++;

		HashMap<String,String> loop_details = new HashMap<>();
		loop_details.put(CONDITIONAL_TEXT,conditionLabel);
		loop_details.put(END_ITERATION_TEXT,endIterationLabel);
		loop_details.put(EXIT_TEXT,exitLabel);
		context.addLoop(loop_details);

		//check condition
		bytecodes.add(new Bytecode.Label(conditionLabel));
		translate(stmt.getCondition(),context,bytecodes);
		bytecodes.add(new Bytecode.If(IfMode.NE, trueLabel));
		bytecodes.add(new Bytecode.Goto(exitLabel));
		//cond is true, run body and increment
		bytecodes.add(new Bytecode.Label(trueLabel));

		for(Stmt s:stmt.getBody()){
			translate(s,context,bytecodes);
		}
		//increment
		bytecodes.add(new Bytecode.Label(endIterationLabel));
		translate(stmt.getIncrement(),context,bytecodes);
		//check cond
		bytecodes.add(new Bytecode.Goto(conditionLabel));

		bytecodes.add(new Bytecode.Label(exitLabel));

		context.removeLoop();
	}

	private Integer convertComparisonOperator(Expr.BOp operator){
		switch (operator){
			case AND:
				return Bytecode.BinOp.AND;
			case OR:
				return Bytecode.BinOp.OR;
			case ADD:
				return Bytecode.BinOp.ADD;
			case SUB:
				return Bytecode.BinOp.SUB;
			case MUL:
				return Bytecode.BinOp.MUL;
			case DIV:
				return Bytecode.BinOp.DIV;
			case REM:
				return Bytecode.BinOp.REM;
			case EQ:
				return Bytecode.IfCmp.EQ;
			case NEQ:
				return Bytecode.IfCmp.NE;
			case LT:
				return Bytecode.IfCmp.LT;
			case LTEQ:
				return Bytecode.IfCmp.LE;
			case GT:
				return Bytecode.IfCmp.GT;
			case GTEQ:
				return Bytecode.IfCmp.GE;
		}
		throw new RuntimeException("Error: got unexpected operator "+operator);
	}

	private void translate(Stmt.IfElse stmt, Context context, List<Bytecode> bytecodes) {
		String trueBranch = freshLabel();
		String exitLabel = freshLabel();
		translate(stmt.getCondition(),context,bytecodes);
		bytecodes.add(new Bytecode.If(IfMode.NE, trueBranch));
		// translate the false branch
		translate(stmt.getFalseBranch(),context,bytecodes);
		if(!allPathsReturn(stmt.getFalseBranch())) {
			bytecodes.add(new Bytecode.Goto(exitLabel));
		}
		// translate true branch
		bytecodes.add(new Bytecode.Label(trueBranch));
		translate(stmt.getTrueBranch(),context,bytecodes);
		bytecodes.add(new Bytecode.Label(exitLabel));
	}

	private void translate(Stmt.While stmt, Context context, List<Bytecode> bytecodes) {

		String trueLabel, exitLabel,conditionLabel;
		conditionLabel = freshLabel()+"_conditional";
		trueLabel = freshLabel()+"_true";
		exitLabel = freshLabel()+"_exit";

		HashMap<String,String> loop_details = new HashMap<>();
		loop_details.put(CONDITIONAL_TEXT,conditionLabel);
		loop_details.put(END_ITERATION_TEXT,conditionLabel);
		loop_details.put(EXIT_TEXT,exitLabel);
		context.addLoop(loop_details);

		//check condition
		bytecodes.add(new Bytecode.Label(conditionLabel));
		translate(stmt.getCondition(),context,bytecodes);
		bytecodes.add(new Bytecode.If(IfMode.NE, trueLabel));
		bytecodes.add(new Bytecode.Goto(exitLabel));
		//cond is true, run body and increment
		bytecodes.add(new Bytecode.Label(trueLabel));
		for(Stmt s:stmt.getBody()){
			translate(s ,context,bytecodes);
		}
		//check cond
		bytecodes.add(new Bytecode.Goto(conditionLabel));

		bytecodes.add(new Bytecode.Label(exitLabel));

		context.removeLoop();
	}

	private void translate(Stmt.Return stmt, Context context, List<Bytecode> bytecodes) {
		Expr expr = stmt.getExpr();
		if(expr != null) {
			// Determine type of returned expression
			Attribute.Type attr = expr.attribute(Attribute.Type.class);
			// Translate returned expression
			translate(expr,context,bytecodes);
			// Add return bytecode
			bytecodes.add(new Bytecode.Return(toJvmType(attr.type)));

		} else {
			bytecodes.add(new Bytecode.Return(null));
		}
	}

	private void translate(Stmt.Switch stmt, Context context, List<Bytecode> bytecodes) {

//		print_byte_info(new JvmType.Int(),context,bytecodes);
		String exitLabel,conditionLabel;
		conditionLabel = freshLabel()+"_conditional";
		exitLabel = freshLabel()+"_exit";

		HashMap<String,String> loop_details = new HashMap<>();
		loop_details.put(CONDITIONAL_TEXT,conditionLabel);
		loop_details.put(EXIT_TEXT,exitLabel);
		context.addLoop(loop_details);

		Attribute.Type attr = stmt.getExpr().attribute(Attribute.Type.class);
		JvmType type = toJvmType(attr.type);


		//Generate labels for each case
		HashMap<Stmt.Case,String> case_to_label = new HashMap<>();
		for(Stmt.Case switch_case:stmt.getCases()){
			String label;
			if(switch_case.isDefault()) {
				label = freshLabel() + "_default";
			}else{
				label = freshLabel() + "_" + switch_case.getValue().toString();
			}
			case_to_label.put(switch_case,label);
		}


		for(Stmt.Case switch_case:stmt.getCases()){
			String switch_body_label = case_to_label.get(switch_case);
			//put value on stack
			if(switch_case.isDefault()){
//				System.out.println("default: "+switch_case.toString());
				bytecodes.add(new Bytecode.Goto(switch_body_label));
			}else {

				switch_case.getValue().attributes().add(new Attribute.Type(attr.type));

				Expr.Binary b = new Expr.Binary(Expr.BOp.EQ,stmt.getExpr(),switch_case.getValue());

				translate(b,context,bytecodes);

				//if not equal to 0(False) i.e is True, then go to stmt branch
				bytecodes.add(new Bytecode.If(IfMode.NE, switch_body_label));


			}
		}
		//after checking all cases with no break or return, should go to exit
		bytecodes.add(new Bytecode.Goto(context.viewMostRecentLoop().get(EXIT_TEXT)));

		//label: body
		for(Stmt.Case switch_case:stmt.getCases()){
			//label for body
			bytecodes.add(new Bytecode.Label(case_to_label.get(switch_case)));
			translate(switch_case.getBody(),context,bytecodes);
		}


		//any break will go to here
		bytecodes.add(new Bytecode.Label(context.viewMostRecentLoop().get(EXIT_TEXT)));

		context.removeLoop();

	}

	private void translate(Stmt.VariableDeclaration stmt, Context context, List<Bytecode> bytecodes) {
		Expr rhs = stmt.getExpr();
		// Declare the variable in the context
		context.declareRegister(stmt.getName());
		//
		if(rhs != null) {
			Expr.LVal lhs = new Expr.Variable(stmt.getName());
			translateAssignmentHelper(lhs,rhs,context,bytecodes);
		}
	}

	/**
	 * Implement an assignment from a given expression to a given lval. This
	 * code is split out because it is used both in translating assignment
	 * statements and variable declarations. In particular, this code is pretty
	 * tricky to get right because it needs to handle cloning of compound data,
	 * and boxing of primitive data (in some cases).
	 *
	 * @param lhs
	 *            Expression being assigned to
	 * @param rhs
	 *            Expression being assigned
	 * @param context
	 *            The current translation context
	 * @param bytecodes
	 *            The list of bytecodes being accumulated
	 */
	private void translateAssignmentHelper(Expr.LVal lhs, Expr rhs, Context context,
			List<Bytecode> bytecodes) {
		// Determine type of assigned expression
		Attribute.Type attr = rhs.attribute(Attribute.Type.class);
		JvmType rhsType = toJvmType(attr.type);
		//
		if (lhs instanceof Expr.Variable) {
			Expr.Variable var = (Expr.Variable) lhs;
			//load the rhs
			translate(rhs, context, bytecodes);
			cloneAsNecessary(rhsType,bytecodes);
			int register = context.getRegister(var.getName());
			bytecodes.add(new Bytecode.Store(register, rhsType));
		}else if(lhs instanceof Expr.IndexOf){
			Expr.IndexOf expr = (Expr.IndexOf)lhs;
			//put array on stack
			translate(expr.getSource(),context,bytecodes);

			//index
			translate(expr.getIndex(),context,bytecodes);
			//value
			translate(rhs,context,bytecodes);
			//convert to object
			boxAsNecessary(attr.type,bytecodes);

			JvmType.Function boxMethodType =
					new JvmType.Function(JvmTypes.JAVA_LANG_OBJECT,JvmTypes.INT,JvmTypes.JAVA_LANG_OBJECT);

			//returns element
			bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_ARRAYLIST, "set", boxMethodType,VIRTUAL));

			//pop element returned by set, we do not need it.
			bytecodes.add(new Bytecode.Pop(JvmTypes.JAVA_LANG_BOOLEAN));
		}else if(lhs instanceof Expr.RecordAccess){
			Expr.RecordAccess access = (Expr.RecordAccess)lhs;

			//put map on stack
			translate(access.getSource(),context,bytecodes);

			putInHashMap(access.getName(),rhs,context,bytecodes);
			//load the rhs
			translate(rhs, context, bytecodes);
			cloneAsNecessary(rhsType,bytecodes);


		} else {
			throw new IllegalArgumentException("unknown lval encountered: "+lhs.toString());
		}
	}

	/**
	 * Translate a given expression in the While language into a series of one
	 * of more bytecodes which implement its behaviour. The result of the
	 * expression should be left on the top of the stack.
	 *
	 * @param stmts
	 * @param bytecodes
	 */
	private void translate(Expr expr, Context context, List<Bytecode> bytecodes) {
		if(expr instanceof Expr.ArrayGenerator) {
			translate((Expr.ArrayGenerator) expr, context, bytecodes);
		} else if(expr instanceof Expr.ArrayInitialiser) {
			translate((Expr.ArrayInitialiser) expr, context, bytecodes);
		} else if(expr instanceof Expr.Binary) {
			translate((Expr.Binary) expr, context, bytecodes);
		} else if(expr instanceof Expr.Literal) {
			translate((Expr.Literal) expr, context, bytecodes);
		} else if(expr instanceof Expr.IndexOf) {
			translate((Expr.IndexOf) expr, context, bytecodes);
		} else if(expr instanceof Expr.Invoke) {
			translate((Expr.Invoke) expr, context, bytecodes);
		} else if(expr instanceof Expr.RecordAccess) {
			translate((Expr.RecordAccess) expr, context, bytecodes);
		} else if(expr instanceof Expr.RecordConstructor) {
			translate((Expr.RecordConstructor) expr, context, bytecodes);
		} else if(expr instanceof Expr.Unary) {
			translate((Expr.Unary) expr, context, bytecodes);
		} else if(expr instanceof Expr.Variable) {
			translate((Expr.Variable) expr, context, bytecodes);
		} else {
			throw new IllegalArgumentException("Unknown expression encountered: " + expr);
		}
	}

	private void translate(Expr.ArrayGenerator expr, Context context, List<Bytecode> bytecodes) {
		int array_reg_index = createArray(context,bytecodes);

		//declare my own variable for keeping count
		//wont overlap with existing as fresh label and contains char that variables cannot have
		String increment_name = freshLabel()+"!";
		context.declareRegister(increment_name);
		int increment_register = context.getRegister(increment_name);
		//store 0 in increment
		bytecodes.add(new Bytecode.LoadConst(0));
		bytecodes.add(new Bytecode.Store(increment_register, new JvmType.Int()));


		String trueLabel, exitLabel,conditionLabel;
		conditionLabel = freshLabel()+"_conditional";
		trueLabel = freshLabel()+"_true";
		exitLabel = freshLabel()+"_exit";

		//condition start
		bytecodes.add(new Bytecode.Label(conditionLabel));
		translate(expr.getSize(),context,bytecodes);
		bytecodes.add(new Bytecode.Load(increment_register,new JvmType.Int()));
		//check condition
		bytecodes.add(new Bytecode.IfCmp(Bytecode.IfCmp.GT, new JvmType.Int(), trueLabel));
		bytecodes.add(new Bytecode.Goto(exitLabel));
		bytecodes.add(new Bytecode.Label(trueLabel));
		//add item here
		putInArray(expr.getValue(),array_reg_index,context,bytecodes);

		//increment
		bytecodes.add(new Bytecode.Iinc(increment_register,1));
		//final stuff
		bytecodes.add(new Bytecode.Goto(conditionLabel));
		bytecodes.add(new Bytecode.Label(exitLabel));

		//leave on top of array
		bytecodes.add(new Bytecode.Load(array_reg_index, JAVA_UTIL_ARRAYLIST));
	}

	private void translate(Expr.ArrayInitialiser expr, Context context, List<Bytecode> bytecodes) {

		int reg_index = createArray(context,bytecodes);

		for(Expr e:expr.getArguments()){
			putInArray(e,reg_index,context,bytecodes);
		}
		//leave on top of array
		bytecodes.add(new Bytecode.Load(reg_index, JAVA_UTIL_ARRAYLIST));

	}

	/**
	 * Create an array, returns the register index
	 * @return
	 */
	private int createArray(Context context,List<Bytecode> bytecodes){
		constructObject(JAVA_UTIL_ARRAYLIST,bytecodes);

		String name = freshLabel()+"_array";
		int reg_index = context.declareRegister(name);
		bytecodes.add(new Bytecode.Store(reg_index,JAVA_UTIL_ARRAYLIST));
		return reg_index;
	}

	/**
	 * Create an array, returns the register index
	 * @return
	 */
	private int createHashMap(Context context,List<Bytecode> bytecodes){
		constructObject(JAVA_UTIL_HASHMAP,bytecodes);

		String name = freshLabel()+"_hashmap";
		int reg_index = context.declareRegister(name);
		bytecodes.add(new Bytecode.Store(reg_index,JAVA_UTIL_HASHMAP));
		return reg_index;
	}

	private void putInArray(Expr e,int array_reg_index,Context context,List<Bytecode> bytecodes){
		Attribute.Type attr = e.attribute(Attribute.Type.class);
		Type t = attr.type;
		//get array
		bytecodes.add(new Bytecode.Load(array_reg_index, JAVA_UTIL_ARRAYLIST));
		//put on stack
		translate(e,context,bytecodes);
		//convert to object
		//FIXME added this line to literal but not sure if correct
		boxAsNecessary(t,bytecodes);
		JvmType.Function boxMethodType =
				new JvmType.Function(new JvmType.Primitive.Bool(), JvmTypes.JAVA_LANG_OBJECT);

		//add object to array
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_ARRAYLIST, "add", boxMethodType,VIRTUAL));
		//pop
		bytecodes.add(new Bytecode.Pop(JvmTypes.JAVA_LANG_BOOLEAN));

	}

	private void translateCollectionCompare(JvmType.Reference type, Expr.BOp operator, Context context, List<Bytecode> bytecodes) {
		//check equality
		JvmType.Function boxMethodType =
				new JvmType.Function(new JvmType.Primitive.Bool(), JvmTypes.JAVA_LANG_OBJECT);

		//returns 0(False) or 1(True)
		bytecodes.add(new Bytecode.Invoke(type, "equals", boxMethodType,VIRTUAL));

		switch (operator){
			case EQ:
				return;
			case NEQ:
				translateNotHelper(bytecodes);
				return;
			default:
				throw new IllegalArgumentException("unknown binary operator encountered");
		}
	}


	private void translate(Expr.Binary expr, Context context, List<Bytecode> bytecodes) {
		String exitLabel = freshLabel()+"_exit_"+expr.getOp();
		//come here
		Attribute.Type lhs_attr = expr.getLhs().attribute(Attribute.Type.class);
		JvmType lhs_type = toJvmType(lhs_attr.type);
		Attribute.Type rhs_attr = expr.getRhs().attribute(Attribute.Type.class);
		JvmType rhs_type = toJvmType(rhs_attr.type);

		Expr lhs = expr.getLhs();
		Expr rhs = expr.getRhs();


		String doRhs = freshLabel()+"do_rhs";
		if(expr.getOp() == Expr.BOp.AND){
			//if first cond fails, exit.
			translate(lhs,context,bytecodes);
			bytecodes.add(new Bytecode.If(IfMode.NE,doRhs));
			bytecodes.add(new Bytecode.LoadConst(false));
			bytecodes.add(new Bytecode.Goto(exitLabel));
		}
		bytecodes.add(new Bytecode.Label(doRhs));
		translate(lhs,context,bytecodes);
		translate(rhs,context,bytecodes);


		//if an array or record
		if(lhs_type == JAVA_UTIL_ARRAYLIST || rhs_type == JAVA_UTIL_ARRAYLIST || lhs_type == JAVA_UTIL_HASHMAP || rhs_type == JAVA_UTIL_HASHMAP){
			//only attempt comparison if of same type
			if(lhs_type != rhs_type){
				switch (expr.getOp()){
					case EQ:
						bytecodes.add(new Bytecode.LoadConst(false));
						return;
					case NEQ:
						bytecodes.add(new Bytecode.LoadConst(true));
						return;
					default:
						throw new IllegalArgumentException("unknown binary operator encountered");
				}
			}else {
				translateCollectionCompare((JvmType.Reference) lhs_type, expr.getOp(), context, bytecodes);
			}
			return;
		}


		int binOp = convertComparisonOperator(expr.getOp());
		switch (expr.getOp()) {
			case AND:
			case OR:
			case ADD:
			case SUB:
			case MUL:
			case DIV:
			case REM:
				bytecodes.add(new Bytecode.BinOp(binOp, lhs_type));
				break;
			case EQ:
			case NEQ:
			case LT:
			case LTEQ:
			case GT:
			case GTEQ:
				String trueLabel = freshLabel()+"_true_"+binOp;

				bytecodes.add(new Bytecode.IfCmp(binOp, lhs_type, trueLabel));
				bytecodes.add(new Bytecode.LoadConst(false));
				bytecodes.add(new Bytecode.Goto(exitLabel));
				bytecodes.add(new Bytecode.Label(trueLabel));
				bytecodes.add(new Bytecode.LoadConst(true));

				break;
			default:
				throw new IllegalArgumentException("unknown binary operator encountered");
		}
		bytecodes.add(new Bytecode.Label(exitLabel));

	}

	private void translate(Expr.Literal expr, Context context, List<Bytecode> bytecodes) {
		Object value = expr.getValue();

		Attribute.Type attr = expr.attribute(Attribute.Type.class);
		Type t = attr.type;
		while(t instanceof Type.Named){
			t = this.declaredTypes.get(((Type.Named) t).getName());
		}

		if(t instanceof Type.Array){
			if(value instanceof String) {//i.e "HELLO"
				String s = (String) value;
				ArrayList<Expr> arr_args = new ArrayList<>();
				ArrayList<Attribute> char_attributes = new ArrayList<>();
				char_attributes.add(new Attribute.Type(new Type.Int()));
				for (char c : s.toCharArray()) {
					Expr expr_c = new Expr.Literal(c, char_attributes);
					arr_args.add(expr_c);
				}

				Expr arr = new Expr.ArrayInitialiser(arr_args, new Attribute.Type(new Type.Array(new Type.Int())));
				translate(arr, context, bytecodes);
			}else if(value instanceof List){//i.e [1]
				Type element_type = ((Type.Array) t).getElement();
				int array_reg_index = createArray(context,bytecodes);
				//convert to expr, put in array
				for(Object o:(List)value){
					putObjectInArray(o,element_type,array_reg_index,bytecodes);
				}
				bytecodes.add(new Bytecode.Load(array_reg_index, JAVA_UTIL_ARRAYLIST));
			}

		}else if(t instanceof Type.Record){
			if(value instanceof Map) {
				//map name to type
				HashMap<String,Type> name_to_type = new HashMap<>();
				for(Pair<Type,String> p : ((Type.Record) t).getFields()){
					name_to_type.put(p.second(),p.first());
				}

				int map_reg_index = createHashMap(context, bytecodes);
				for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
					String name = entry.getKey();
					putObjectInHashMap(name,entry.getValue(),name_to_type.get(name),map_reg_index,bytecodes);
				}
				bytecodes.add(new Bytecode.Load(map_reg_index, JAVA_UTIL_HASHMAP));

			}else{
				throw new RuntimeException("unknown value of record type");
			}
		}else{

			bytecodes.add(new Bytecode.LoadConst(value));

		}

	}

	public void putObjectInArray(Object o,Type t,int array_reg_index,List<Bytecode> bytecodes){

		//get array
		bytecodes.add(new Bytecode.Load(array_reg_index, JAVA_UTIL_ARRAYLIST));
		//put on stack
		bytecodes.add(new Bytecode.LoadConst(o));
		boxAsNecessary(t,bytecodes);
		//convert to object
		JvmType.Function boxMethodType =
				new JvmType.Function(new JvmType.Primitive.Bool(), JvmTypes.JAVA_LANG_OBJECT);
		//add object to array
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_ARRAYLIST, "add", boxMethodType,VIRTUAL));
		//pop
		bytecodes.add(new Bytecode.Pop(JvmTypes.JAVA_LANG_BOOLEAN));


	}

	private void translate(Expr.IndexOf expr, Context context, List<Bytecode> bytecodes) {
		//getting value out of array

		//put array on stack
		translate(expr.getSource(),context,bytecodes);

		//put index on stack
		translate(expr.getIndex(),context,bytecodes);

		//get element at index
		JvmType.Function boxMethodType =
				new JvmType.Function(JvmTypes.JAVA_LANG_OBJECT,JvmTypes.INT);

		//returns 0(False) or 1(True)
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_ARRAYLIST, "get", boxMethodType,VIRTUAL));

		//this should be an array type
		Attribute.Type attr = expr.attribute(Attribute.Type.class);
		addReadConversion(attr.type,bytecodes);

	}

	private void translate(Expr.Invoke expr, Context context, List<Bytecode> bytecodes) {
		JvmType.Function type = methodTypes.get(expr.getName());
		List<Expr> arguments = expr.getArguments();
		for(int i=0;i!=arguments.size();++i) {
			Expr e =  arguments.get(i);
			translate(e,context,bytecodes);
			Attribute.Type attr = e.attribute(Attribute.Type.class);
			JvmType arg_type = toJvmType(attr.type);
			cloneAsNecessary(arg_type,bytecodes);
		}
		bytecodes.add(new Bytecode.Invoke(context.getEnclosingClass(), expr.getName(), type, STATIC));
	}

	private void translate(Expr.RecordAccess expr, Context context, List<Bytecode> bytecodes) {
		//get hashmap
		translate(expr.getSource(),context,bytecodes);
		//put string
		bytecodes.add(new Bytecode.LoadConst(expr.getName()));
		//get method
		JvmType.Function boxMethodType =
				new JvmType.Function(JvmTypes.JAVA_LANG_OBJECT, JvmTypes.JAVA_LANG_OBJECT);

		//add object to array
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_HASHMAP, "get", boxMethodType,VIRTUAL));

		//cast to expected type
		Attribute.Type attr = expr.attribute(Attribute.Type.class);
		Type t = attr.type;
		addReadConversion(t,bytecodes);

	}

	private void putInHashMap(String name, Expr value,Context context,List<Bytecode> bytecodes){
		//put String on stack
		bytecodes.add(new Bytecode.LoadConst(name));
		//put val on stack
		translate(value,context,bytecodes);
		Attribute.Type attr = value.attribute(Attribute.Type.class);
		Type t = attr.type;
		//convert  to object
		boxAsNecessary(t,bytecodes);
		cloneAsNecessary(toJvmType(t),bytecodes);

		JvmType.Function boxMethodType =
				new JvmType.Function(JvmTypes.JAVA_LANG_OBJECT, JvmTypes.JAVA_LANG_OBJECT,JvmTypes.JAVA_LANG_OBJECT);

		//add object to array
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_HASHMAP, "put", boxMethodType,VIRTUAL));
		//pop
		bytecodes.add(new Bytecode.Pop(JvmTypes.JAVA_LANG_OBJECT));
	}

	private void putObjectInHashMap(String name, Object value,Type t,int map_reg_index,List<Bytecode> bytecodes){
		bytecodes.add(new Bytecode.Load(map_reg_index, JAVA_UTIL_HASHMAP));
		//put String on stack
		bytecodes.add(new Bytecode.LoadConst(name));
		//put val on stack
		bytecodes.add(new Bytecode.LoadConst(value));
		boxAsNecessary(t,bytecodes);


		JvmType.Function boxMethodType =
				new JvmType.Function(JvmTypes.JAVA_LANG_OBJECT, JvmTypes.JAVA_LANG_OBJECT,JvmTypes.JAVA_LANG_OBJECT);

		//add object to array
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_HASHMAP, "put", boxMethodType,VIRTUAL));
		//pop
		bytecodes.add(new Bytecode.Pop(JvmTypes.JAVA_LANG_OBJECT));
	}

	private void translate(Expr.RecordConstructor expr, Context context, List<Bytecode> bytecodes) {
		int map_reg_index = createHashMap(context,bytecodes);
		for (Pair<String, Expr> field : expr.getFields()) {
			//get map
			bytecodes.add(new Bytecode.Load(map_reg_index, JAVA_UTIL_HASHMAP));
			putInHashMap(field.first(),field.second(),context,bytecodes);
		}
		//leave on top of array
		bytecodes.add(new Bytecode.Load(map_reg_index, JAVA_UTIL_HASHMAP));

	}



	private void translate(Expr.Unary expr, Context context, List<Bytecode> bytecodes) {
		translate(expr.getExpr(),context,bytecodes);
		switch(expr.getOp()) {
		case NOT: {
			translateNotHelper(bytecodes);
			break;
		}
		case NEG:
			bytecodes.add(new Bytecode.Neg(JvmTypes.INT));
			break;
		case LENGTHOF:
			JvmType.Function boxMethodType =
					new JvmType.Function(JvmTypes.INT);

			//returns 0(False) or 1(True)
			bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_ARRAYLIST, "size", boxMethodType,VIRTUAL));

			break;
		default:
			throw new IllegalArgumentException("unknown unary operator encountered: "+expr.toString());
		}
	}

	private void translateNotHelper(List<Bytecode> bytecodes) {
		String trueBranch = freshLabel();
		String exitLabel = freshLabel();
		bytecodes.add(new Bytecode.If(IfMode.EQ, trueBranch));
		bytecodes.add(new Bytecode.LoadConst(false));
		bytecodes.add(new Bytecode.Goto(exitLabel));
		bytecodes.add(new Bytecode.Label(trueBranch));
		bytecodes.add(new Bytecode.LoadConst(true));
		bytecodes.add(new Bytecode.Label(exitLabel));
	}

	private void translate(Expr.Variable expr, Context context, List<Bytecode> bytecodes) {
		// Determine type of variable
		Attribute.Type attr = expr.attribute(Attribute.Type.class);
		JvmType type = toJvmType(attr.type);
		int register = context.getRegister(expr.getName());
		bytecodes.add(new Bytecode.Load(register, type));
	}

	/**
	 * This method is responsible for ensuring that the last bytecode in a
	 * method is a return bytecode. This is only necessary (and valid) in the
	 * case of a method which returns void.
	 *
	 * @param body
	 * @param bytecodes
	 */
	private void addReturnAsNecessary(WhileFile.MethodDecl md, List<Bytecode> bytecodes) {
		if(!allPathsReturn(md.getBody())) {
			bytecodes.add(new Bytecode.Return(null));
		}
	}

	/**
	 * Check whether every path through a given statement block ends in a return
	 * or not.  This is helpful in a few places.
	 *
	 * @param stmts
	 * @return
	 */
	private boolean allPathsReturn(List<Stmt> stmts) {
		for(Stmt stmt : stmts) {
			if(allPathsReturn(stmt)) {
				return true;
			}
		}
		return false;
	}

	private boolean allPathsReturn(Stmt stmt) {
		if(stmt instanceof Stmt.IfElse) {
			Stmt.IfElse ife = (Stmt.IfElse) stmt;
			return allPathsReturn(ife.getTrueBranch()) && allPathsReturn(ife.getFalseBranch());
		} else if(stmt instanceof Stmt.Return) {
			return true;
		}
		return false;
	}

	/**
	 * Clone the element on top of the stack, if it is of an appropriate type
	 * (i.e. is not a primitive).
	 *
	 * @param type
	 *            The type of the element on the top of the stack.
	 * @param context
	 *            The current translation context
	 * @param bytecodes
	 *            The list of bytecodes being accumulated
	 */
	private void cloneAsNecessary(JvmType type, List<Bytecode> bytecodes) {
		if(type instanceof JvmType.Primitive || type == JvmTypes.JAVA_LANG_STRING) {
			// no need to do anything in the case of a primitive type
		} else {
			// Invoke the clone function on the datatype in question
			JvmType.Function ft = new JvmType.Function(JvmTypes.JAVA_LANG_OBJECT);
			bytecodes.add(new Bytecode.Invoke((JvmType.Reference) type, "clone", ft, Bytecode.InvokeMode.VIRTUAL));
			bytecodes.add(new Bytecode.CheckCast(type));
		}
	}

	/**
	 * Box the element on top of the stack, if it is of an appropriate type
	 * (i.e. is not a primitive).
	 *
	 * @param from
	 *            The type of the element we are converting from (i.e. on the
	 *            top of the stack).
	 * @param context
	 *            The current translation context
	 * @param bytecodes
	 *            The list of bytecodes being accumulated
	 */
	private void boxAsNecessary(Type from, List<Bytecode> bytecodes) {
		JvmType.Clazz owner;
		JvmType jvmType = toJvmType(from);

		if(jvmType instanceof JvmType.Reference) {
			// Only need to box primitive types
			return;
		} else if(jvmType instanceof JvmType.Bool) {
			owner = JvmTypes.JAVA_LANG_BOOLEAN;
		} else if(jvmType instanceof JvmType.Char) {
			owner = JvmTypes.JAVA_LANG_CHARACTER;
		} else if(jvmType instanceof JvmType.Int) {
			owner = JvmTypes.JAVA_LANG_INTEGER;
		} else {
			throw new IllegalArgumentException("unknown primitive type encountered: " + jvmType);
		}

		String boxMethodName = "valueOf";
		JvmType.Function boxMethodType = new JvmType.Function(owner,jvmType);
		bytecodes.add(new Bytecode.Invoke(owner, boxMethodName, boxMethodType,
				STATIC));
	}

	/**
	 * The element on the top of the stack has been read out of a compound data
	 * structure, such as an ArrayList or HashMap representing an array or
	 * record. This value has type Object, and we want to convert it into its
	 * correct form. At a minimum, this requires casting it into the expected
	 * type. This may also require unboxing the element if it is representing a
	 * primitive type.
	 *
	 * @param to
	 *            The type of the element we are converting to (i.e. that we
	 *            want to be on the top of the stack).
	 * @param bytecodes
	 *            The list of bytecodes being accumulated
	 */
	private void addReadConversion(Type to, List<Bytecode> bytecodes) {
		// First cast to the boxed jvm type
		JvmType.Reference boxedJvmType = toBoxedJvmType(to);
		bytecodes.add(new Bytecode.CheckCast(boxedJvmType));
		// Second, unbox as necessary
		unboxAsNecessary(boxedJvmType,bytecodes);
	}

	/**
	 * Unbox a reference type when appropriate. That is, when it represented a
	 * boxed primitive type.
	 *
	 * @param jvmType
	 * @param bytecodes
	 */
	private void unboxAsNecessary(JvmType.Reference jvmType, List<Bytecode> bytecodes) {
		String unboxMethodName;
		JvmType.Primitive unboxedJvmType;

		if (jvmType.equals(JvmTypes.JAVA_LANG_BOOLEAN)) {
			unboxMethodName = "booleanValue";
			unboxedJvmType = JvmTypes.BOOL;
		} else if (jvmType.equals(JvmTypes.JAVA_LANG_CHARACTER)) {
			unboxMethodName = "charValue";
			unboxedJvmType = JvmTypes.CHAR;
		} else if (jvmType.equals(JvmTypes.JAVA_LANG_INTEGER)) {
			unboxMethodName = "intValue";
			unboxedJvmType = JvmTypes.INT;
		} else {
			return; // not necessary to unbox
		}
		JvmType.Function unboxMethodType = new JvmType.Function(unboxedJvmType);
		bytecodes.add(new Bytecode.Invoke(jvmType, unboxMethodName, unboxMethodType, Bytecode.InvokeMode.VIRTUAL));
	}

	/**
	 * The construct method provides a generic way to construct a Java object
	 * using a default constructor which accepts no arguments.
	 *
	 * @param owner
	 *            The class type to construct
	 * @param bytecodes
	 *            The list of bytecodes being accumulated
	 */
	private void constructObject(JvmType.Clazz owner, List<Bytecode> bytecodes) {
		bytecodes.add(new Bytecode.New(owner));
		bytecodes.add(new Bytecode.Dup(owner));
		JvmType.Function ftype = new JvmType.Function(JvmTypes.VOID, Collections.EMPTY_LIST);
		bytecodes.add(new Bytecode.Invoke(owner, "<init>", ftype, Bytecode.InvokeMode.SPECIAL));
	}

	/**
	 * Construct the JVM function type for this method declaration.
	 *
	 * @param method
	 * @return
	 */
	private JvmType.Function constructMethodType(WhileFile.MethodDecl method) {
		List<JvmType> parameterTypes = new ArrayList<JvmType>();
		// Convert each parameter type
		for(WhileFile.Parameter p : method.getParameters()) {
			JvmType jpt = toJvmType(p.getType());
			parameterTypes.add(jpt);
		}
		// convert the return type
		JvmType returnType = toJvmType(method.getRet());
		//
		JvmType.Function ft = new JvmType.Function(returnType, parameterTypes);
		methodTypes.put(method.getName(), ft);
		return ft;
	}

	/**
	 * Construct an initial context for the given method. In essence, this
	 * just maps every parameter to the corresponding JVM register, as these are
	 * automatically assigned by the JVM when the method is in invoked.
	 *
	 * @param method
	 * @return
	 */
	private Map<String,Integer> constructMethodEnvironment(WhileFile.MethodDecl method) {
		HashMap<String,Integer> environment = new HashMap<String,Integer>();
		int index = 0;
		for(WhileFile.Parameter p : method.getParameters()) {
			environment.put(p.getName(), index++);
		}
		return environment;
	}


	/**
	 * Check whether a While type is a primitive or not
	 *
	 * @param type
	 * @return
	 */
	private boolean isPrimitive(Type type) {
		if(type instanceof Type.Record || type instanceof Type.Array) {
			return false;
		} else if(type instanceof Type.Named) {
			Type.Named d = (Type.Named) type;
			return isPrimitive(declaredTypes.get(d.getName()));
		} else {
			return true;
		}
	}

	/**
	 * Get a new label name which has not been used before.
	 *
	 * @return
	 */
	private String freshLabel() {
		return "label" + fresh++;
	}


	private static int fresh = 0;

	/**
	 * Convert a While type into its JVM type.
	 *
	 * @param t
	 * @return
	 */
	private JvmType toJvmType(Type t) {
		if(t instanceof Type.Void) {
			return JvmTypes.VOID;
		} else if(t instanceof Type.Bool) {
			return JvmTypes.BOOL;
		} else if(t instanceof Type.Int) {
			return JvmTypes.INT;
		} else if(t instanceof Type.Named) {
			Type.Named d = (Type.Named) t;
			return toJvmType(declaredTypes.get(d.getName()));
		} else if(t instanceof Type.Array) {
			return JAVA_UTIL_ARRAYLIST;
		} else if(t instanceof Type.Record) {
			return JAVA_UTIL_HASHMAP;
		} else {
			throw new IllegalArgumentException("Unknown type encountered: " + t);
		}
	}

	/**
	 * Convert a While type into its boxed JVM type.
	 *
	 * @param t
	 * @return
	 */
	private JvmType.Reference toBoxedJvmType(Type t) {
		if(t instanceof Type.Bool) {
			return JvmTypes.JAVA_LANG_BOOLEAN;
		} else if(t instanceof Type.Int) {
			return JvmTypes.JAVA_LANG_INTEGER;
		} else if(t instanceof Type.Named) {
			Type.Named d = (Type.Named) t;
			return toBoxedJvmType(declaredTypes.get(d.getName()));
		} else if(t instanceof Type.Array) {
			return JAVA_UTIL_ARRAYLIST;
		} else if(t instanceof Type.Record) {
			return JAVA_UTIL_HASHMAP;
		} else {
			throw new IllegalArgumentException("Unknown type encountered: " + t);
		}
	}

	// A few helpful constants not defined in JvmTypes
	private static final JvmType.Clazz JAVA_UTIL_LIST = new JvmType.Clazz("java.util","List");
	private static final JvmType.Clazz JAVA_UTIL_ARRAYLIST = new JvmType.Clazz("java.util","ArrayList");
	private static final JvmType.Clazz JAVA_UTIL_HASHMAP = new JvmType.Clazz("java.util","HashMap");
	private static final JvmType.Clazz JAVA_UTIL_COLLECTION = new JvmType.Clazz("java.util","Collection");
	private static final JvmType.Clazz JAVA_UTIL_COLLECTIONS = new JvmType.Clazz("java.util","Collections");

	/**
	 * Provides useful contextual information which passed down through the
	 * translation process.
	 *
	 * @author David J. Pearce
	 *
	 */
	private static class Context {
		/**
		 * The type of the enclosing class. This is needed to invoke methods
		 * within the same class.
		 */
		private final JvmType.Clazz enclosingClass;

		/**
		 * Maps each declared variable to a jvm register index
		 */
		private final Map<String,Integer> environment;

		private final Stack<Map<String,String>> loopManagement;



		public Context(JvmType.Clazz enclosingClass, Map<String,Integer> environment) {
			this.enclosingClass = enclosingClass;
			this.environment = environment;
			this.loopManagement = new Stack<>();
		}

		public Context(Context context) {
			this.enclosingClass = context.enclosingClass;
			this.environment = new HashMap<String,Integer>(context.environment);
			this.loopManagement = new Stack<>();
		}

		/**
		 * Get the enclosing class for this translation context.
		 *
		 * @return
		 */
		public JvmType.Clazz getEnclosingClass() {
			return enclosingClass;
		}

		/**
		 * Declare a new variable in the given context. This basically allocated
		 * the given variable to the next available register slot.
		 *
		 * @param var
		 * @param environment
		 * @return
		 */
		public int declareRegister(String var) {
			int register = environment.size();
			environment.put(var, register);
			return register;
		}

		/**
		 * Return the register index associated with a given variable which has
		 * been previously declared.
		 *
		 * @param var
		 * @return
		 */
		public int getRegister(String var) {
			return environment.get(var);
		}

		public void addLoop(HashMap<String,String> loop){
			loopManagement.add(loop);
		}

		public Map<String,String> viewMostRecentLoop(){
			return loopManagement.peek();
		}

		public void removeLoop(){
			loopManagement.pop();
		}

	}



}
