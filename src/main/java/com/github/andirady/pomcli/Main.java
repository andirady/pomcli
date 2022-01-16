package com.github.andirady.pomcli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "pom", subcommandsRepeatable = true, subcommands = {IdCommand.class, AddCommand.class, SearchCommand.class})
public class Main {

	public static void main(String[] args) {
		var cli = new CommandLine(new Main());
		cli.registerConverter(QuerySpec.class, Main::stringToQuerySpec);
		System.exit(cli.execute(args));
	}

	private static QuerySpec stringToQuerySpec(String s) {
		var qs = QuerySpec.of(s);
		if (qs.groupId() == null) {
			throw new CommandLine.TypeConversionException("Invalid format: missing groupId for '" + s + "'");
		}

		return qs;
	}
}