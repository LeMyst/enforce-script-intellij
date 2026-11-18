class Preview
{
	void Run(Object item)
	{
		GetGame().GetCallQueue(CALL_CATEGORY_SYSTEM).CallLater(Run, 1000, false, item);
	}
}
