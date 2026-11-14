package studio.timeandtime.claimmap;

/** Outcome of a money operation. Business failures are values, not exceptions. */
public enum TxResult {
	OK,
	INSUFFICIENT,
	INVALID_AMOUNT,
	FROZEN,
	NO_ACCOUNT,
	OVERFLOW,
	SAME_ACCOUNT,
	IO_ERROR
}
