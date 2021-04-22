package redempt.crunch;

import redempt.crunch.exceptions.ExpressionCompilationException;
import redempt.crunch.functional.ArgumentList;
import redempt.crunch.functional.EvaluationEnvironment;
import redempt.crunch.functional.Function;
import redempt.crunch.functional.FunctionCall;
import redempt.crunch.token.LiteralValue;
import redempt.crunch.token.Operation;
import redempt.crunch.token.Operator;
import redempt.crunch.token.Token;
import redempt.crunch.token.Value;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.TreeSet;

class ExpressionCompiler {
	
	private static final char VAR_CHAR = '$';
	
	static CompiledExpression compile(String expression, EvaluationEnvironment env) {
		CompiledExpression exp = new CompiledExpression();
		Value val = compileValue(expression, exp, env, 0, false).getFirst();
		exp.setValue(val);
		return exp;
	}
	
	private static Pair<Value, Integer> compileValue(String expression, CompiledExpression exp, EvaluationEnvironment env, int begin, boolean parenthetical) {
		CharTree<Token> namedTokens = env.getNamedTokens();
		LinkedList<Token> tokens = new LinkedList<>();
		Pair<Token, Integer> firstOp = namedTokens.getFrom(expression, begin);
		boolean op = firstOp.getFirst() != null && firstOp.getFirst().getType() == TokenType.OPERATOR;
		boolean closed = false;
		int tokenStart = begin;
		char[] chars = expression.toCharArray();
		int i;
		loop:
		for (i = begin; i < expression.length(); i++) {
			char c = chars[i];
			switch (c) {
				case '(':
					if (tokens.size() > 0 && tokens.getLast().getType() == TokenType.FUNCTION) {
						Pair<ArgumentList, Integer> args = compileArgumentList(expression, exp, env, i + 1);
						tokens.add(args.getFirst());
						i += args.getSecond();
						tokenStart = i;
						op = true;
						continue;
					}
					if (!op && tokenStart != i) {
						tokens.add(compileToken(expression, tokenStart, i, exp));
					}
					Pair<Value, Integer> inner = compileValue(expression, exp, env, i + 1, true);
					i += inner.getSecond() + 1;
					tokens.add(inner.getFirst());
					tokenStart = i;
					op = true;
					continue;
				case ' ':
					if (!op && tokenStart != i) {
						tokens.add(compileToken(expression, tokenStart, i, exp));
						tokenStart = i + 1;
					} else {
						tokenStart++;
					}
					continue;
				case ')':
				case ',':
					if (!parenthetical) {
						throw new ExpressionCompilationException("Unbalanced parenthesis");
					}
					closed = true;
					break loop;
			}
			Pair<Token, Integer> namedToken = namedTokens.getFrom(expression, i);
			if (namedToken.getFirst() != null) {
				Token token = namedToken.getFirst();
				if (token.getType() == TokenType.VARIABLE) {
					Variable var = ((Variable) token).getClone();
					var.expression = exp;
					token = var;
				}
				if (!op && tokenStart != i) {
					tokens.add(compileToken(expression, tokenStart, i, exp));
				}
				if (token == Operator.SUBTRACT && (tokens.size() == 0 || !(tokens.get(tokens.size() - 1) instanceof Value))) {
					token = Operator.NEGATE;
				}
				op = token.getType() == TokenType.OPERATOR;
				i += namedToken.getSecond() - 1;
				tokenStart = i + 1;
				tokens.add(token);
				continue;
			}
			op = false;
		}
		if (parenthetical && !closed) {
			throw new ExpressionCompilationException("Unbalanced parenthesis");
		}
		if (tokenStart < i && i <= expression.length() && !op) {
			tokens.add(compileToken(expression, tokenStart, i, exp));
		}
		return new Pair<>(reduceTokens(tokens), i - begin);
	}
	
	private static Pair<ArgumentList, Integer> compileArgumentList(String expression, CompiledExpression exp, EvaluationEnvironment env, int start) {
		List<Value> values = new ArrayList<>();
		int i = start;
		loop:
		while (i < expression.length() && expression.charAt(i) != ')') {
			Pair<Value, Integer> result = compileValue(expression, exp, env, i, true);
			i += result.getSecond() + 1;
			values.add(result.getFirst());
			switch (expression.charAt(i - 1)) {
				case ')':
					break loop;
				case ',':
					break;
				default:
					throw new ExpressionCompilationException("Function argument lists must be separated by commas");
			}
		}
		if (values.size() == 0) {
			i++;
		}
		if (expression.charAt(i - 1) != ')') {
			throw new ExpressionCompilationException("Unbalanced parenthesis");
		}
		Value[] valueArray = values.toArray(new Value[values.size()]);
		return new Pair<>(new ArgumentList(valueArray), i - start);
	}
	
	private static Value reduceTokens(LinkedList<Token> tokens) {
		TreeSet<Integer> set = new TreeSet<>();
		int max = -1;
		ListIterator<Token> iter = tokens.listIterator();
		while (iter.hasNext()) {
			Token token = iter.next();
			if (token.getType() == TokenType.FUNCTION) {
				if (!iter.hasNext()) {
					throw new ExpressionCompilationException("Function must be followed by argument list");
				}
				Token next = iter.next();
				iter.previous();
				iter.previous();
				if (next.getType() != TokenType.ARGUMENT_LIST) {
					throw new ExpressionCompilationException("Function must be followed by argument list");
				}
				Function func = (Function) token;
				ArgumentList list = (ArgumentList) next;
				if (list.getArguments().length != func.getArgCount()) {
					throw new ExpressionCompilationException("Function '" + func.getName() + "' takes " + func.getArgCount() + " args, but got " + list.getArguments().length);
				}
				iter.remove();
				iter.next();
				iter.set(new FunctionCall(func, list.getArguments()));
				continue;
			}
			if (token.getType() == TokenType.OPERATOR) {
				Operator op = (Operator) token;
				set.add(op.getPriority());
				if (op.getPriority() > max) {
					max = op.getPriority();
				}
			}
		}
		while (set.size() > 0) {
			int priority = set.floor(max);
			iter = tokens.listIterator();
			while (iter.hasNext()) {
				Token token = iter.next();
				if (token.getType() != TokenType.OPERATOR) {
					continue;
				}
				Operator op = (Operator) token;
				if (op.getPriority() != priority) {
					continue;
				}
				createOperation(iter, op);
			}
			set.remove(priority);
		}
		Token token = tokens.getFirst();
		if (!(token instanceof Value)) {
			throw new ExpressionCompilationException("Token is not a value: " + token.toString());
		}
		if (tokens.size() > 1) {
			throw new ExpressionCompilationException("Adjacent values have no operators between them");
		}
		return (Value) tokens.get(0);
	}
	
	private static void createOperation(ListIterator<Token> iter, Operator op) {
		if (!iter.hasNext()) {
			throw new ExpressionCompilationException("Operator " + op + " has no following operand");
		}
		if (op.isUnary()) {
			Token next = iter.next();
			iter.remove();
			iter.previous();
			if (next.getType() == TokenType.OPERATOR) {
				throw new ExpressionCompilationException("Adjacent operators have no values to operate on");
			}
			if (next.getType() == TokenType.LITERAL_VALUE) {
				Value literal = (Value) next;
				iter.set(new LiteralValue(op.operate(literal.getValue())));
				return;
			}
			iter.set(new Operation(op, (Value) next));
			return;
		}
		if (!iter.hasPrevious()) {
			throw new ExpressionCompilationException("Operator " + op + " has no leading operand");
		}
		Token next = iter.next();
		iter.remove();
		iter.previous();
		Token prev = iter.previous();
		iter.remove();
		iter.next();
		if (prev.getType() == TokenType.OPERATOR || next.getType() == TokenType.OPERATOR) {
			throw new ExpressionCompilationException("Adjacent operators have no values to operate on");
		}
		if (prev.getType() == TokenType.LITERAL_VALUE && next.getType() == TokenType.LITERAL_VALUE) {
			Value lit1 = (Value) prev;
			Value lit2 = (Value) next;
			iter.set(new LiteralValue(op.operate(lit1.getValue(), lit2.getValue())));
			return;
		}
		iter.set(new Operation(op, (Value) prev, (Value) next));
	}
	
	private static Token compileToken(String str, int start, int end, CompiledExpression exp) {
		if (str.charAt(start) == VAR_CHAR) {
			return new Variable(exp, FastNumberParsing.parseInt(str, start + 1, end) - 1);
		}
		return new LiteralValue(FastNumberParsing.parseDouble(str, start, end));
	}
	
}
