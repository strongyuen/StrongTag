package net.strong.castor.castor;

public class Datetime2String extends DateTimeCastor<java.util.Date, String> {

	@Override
	public String cast(java.util.Date src, Class<?> toType, String... args) {
		return dateTimeFormat.format(src);
	}

}
